/*
 *  Copyright (c) 2015, Facebook, Inc.
 *  All rights reserved.
 *
 *  This source code is licensed under the BSD-style license found in the
 *  LICENSE file in the root directory of this source tree. An additional grant
 *  of patent rights can be found in the PATENTS file in the same directory.
 *
 */

package com.facebook.network.connectionclass;

import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

/**
 * Class used to read from TrafficStats periodically, in order to determine a ConnectionClass.
 * 设备带宽的采样员
 * 外部可以通过该采样员进行带宽状态的检测
 * 内部会在子线程中每隔一段时间进行一次带宽的计算，并且进行反馈
 */
public class DeviceBandwidthSampler {

    /**
     * The DownloadBandwidthManager that keeps track of the moving average and ConnectionClass.
     */
    private final ConnectionClassManager mConnectionClassManager;
    //原子Integer操作类
    private AtomicInteger mSamplingCounter;
    //当前采样的线程
    private HandlerThread mThread;
    //在子线程(mThread)中执行的Handler，用于进行定时的采样
    private SamplingHandler mHandler;

    //记录上一次读取的时间，这个是系统开机到现在的时间
    private long mLastTimeReading;
    //用于记录上一次采样的时候设备所从网络上收到的包的字节数
    private static long sPreviousBytes = -1;

    // Singleton.静态内部类单例，实际上ConnectionClassManager也是单例
    private static class DeviceBandwidthSamplerHolder {
        public static final DeviceBandwidthSampler instance =
                new DeviceBandwidthSampler(ConnectionClassManager.getInstance());
    }

    /**
     * Retrieval method for the DeviceBandwidthSampler singleton.
     *
     * @return The singleton instance of DeviceBandwidthSampler.
     */
    @Nonnull
    public static DeviceBandwidthSampler getInstance() {
        return DeviceBandwidthSamplerHolder.instance;
    }

    private DeviceBandwidthSampler(
            ConnectionClassManager connectionClassManager) {
        mConnectionClassManager = connectionClassManager;
        //初始为0
        mSamplingCounter = new AtomicInteger();
        //初始化一个子线程并开启
        mThread = new HandlerThread("ParseThread");
        mThread.start();
        //当前SamplingHandler运行在子线程中
        mHandler = new SamplingHandler(mThread.getLooper());
    }

    /**
     * Method call to start sampling for download bandwidth.
     * 开始进行带宽的测量
     */
    public void startSampling() {
        //通过原子增长操作来保证只运行一次
        //实际上用AtomicBoolean也行
        //采样的轮询操作只能开始一次，必须先停止之前的采样，才可以开始新的采样
        if (mSamplingCounter.getAndIncrement() == 0) {
            mHandler.startSamplingThread();//开始进行带宽计算的轮询
            //记录采样开始时间
            mLastTimeReading = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Finish sampling and prevent further changes to the
     * ConnectionClass until another timer is started.
     * 停止采样轮询操作
     */
    public void stopSampling() {
        //当前采样进行中
        if (mSamplingCounter.decrementAndGet() == 0) {
            mHandler.stopSamplingThread();//停止采样的轮询操作
            //后续虽然不在进行轮询计算，但是当前时刻要作最后一次带宽计算，这意味着stop之后有可能有一次的带宽等级变化回调
            addFinalSample();
        }
    }

    /**
     * Method for polling for the change in total bytes since last update and
     * adding it to the BandwidthManager.
     * 计算当前带宽
     * 实际上就是通过每隔一段时间的轮询，进行带宽的计算，从而进行带宽等级变化的回调
     */
    protected void addSample() {
        //先返回当前设备从开机到现在为止所收到的网络传过来的字节数，包括TCP和UDP传输
        long newBytes = TrafficStats.getTotalRxBytes();
        //与上次记录的收到的字节数做差，可以得到这段时间内所收到的字节数
        long byteDiff = newBytes - sPreviousBytes;
        if (sPreviousBytes >= 0) {//当前有旧的数据进行对比
            synchronized (this) {
                //获取当前开机到现在过去的毫秒数
                long curTimeReading = SystemClock.elapsedRealtime();
                //这里就是实际处理变化和计算的逻辑
                mConnectionClassManager.addBandwidth(byteDiff, curTimeReading - mLastTimeReading);
                //记录上一次进行的时间
                mLastTimeReading = curTimeReading;
            }
        }
        //第一次采样的时候没有旧的数据对比，直接记录就好，等待下一次采样的时候
        sPreviousBytes = newBytes;
    }

    /**
     * Resets previously read byte count after recording a sample, so that
     * we don't count bytes downloaded in between sampling sessions.
     */
    protected void addFinalSample() {
        addSample();
        sPreviousBytes = -1;
    }

    /**
     * 当前是否采样中
     * @return True if there are still threads which are sampling, false otherwise.
     */
    public boolean isSampling() {
        return (mSamplingCounter.get() != 0);
    }

    /**
     * 计算用的Handler，不过在这里用是运行在HandlerThread开启的子线程中的
     */
    private class SamplingHandler extends Handler {
        /**
         * Time between polls in ms.
         * 1s轮询一次
         */
        static final long SAMPLE_TIME = 1000;

        static private final int MSG_START = 1;

        public SamplingHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START:
                    addSample();//进行带宽计算
                    //1s发送一条信号，相当于轮询，时间间隔为1s
                    //实际上就是如果开始采样，那么就每隔1s计算一次
                    sendEmptyMessageDelayed(MSG_START, SAMPLE_TIME);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown what=" + msg.what);
            }
        }

        /**
         * 开始采样线程，实际上就是发一个消息到handler中
         * 然后后续handler会隔一段事件发送同一个消息进行轮询
         */
        public void startSamplingThread() {
            sendEmptyMessage(SamplingHandler.MSG_START);
        }

        /**
         * 停止采样线程的进行，handler通过唯一的消息，隔一段时间执行一次
         * 那么只需要将这个唯一的消息移除即可停止
         */
        public void stopSamplingThread() {
            removeMessages(SamplingHandler.MSG_START);
        }
    }
}