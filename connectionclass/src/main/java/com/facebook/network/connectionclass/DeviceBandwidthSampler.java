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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

/**
 * Class used to read from TrafficStats periodically, in order to determine a ConnectionClass.
 * 实际上的执行类，用于确定一个ConnectionClass
 */
public class DeviceBandwidthSampler {

  /**
   * The DownloadBandwidthManager that keeps track of the moving average and ConnectionClass.
   * 一个执行者必须和其需要汇报的领导关联
   */
  private final ConnectionClassManager mConnectionClassManager;
  //原子Integer操作类
  private AtomicInteger mSamplingCounter;

  private SamplingHandler mHandler;
  private HandlerThread mThread;
  //记录上一次读取的时间，这个是系统开机到现在的时间
  private long mLastTimeReading;
  //记录之前使用的流量总数
  private static long sPreviousBytes = -1;

  // Singleton.静态内部类单例
  private static class DeviceBandwidthSamplerHolder {
      public static final DeviceBandwidthSampler instance =
              new DeviceBandwidthSampler(ConnectionClassManager.getInstance());
  }

  /**
   * Retrieval method for the DeviceBandwidthSampler singleton.
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
      //通过原子增长操作来保证只运行一次，很有趣的思路
      if (mSamplingCounter.getAndIncrement() == 0) {
          mHandler.startSamplingThread();
          //记录当前从开机到现在的时间
          mLastTimeReading = SystemClock.elapsedRealtime();
      }
  }

  /**
   * Finish sampling and prevent further changes to the
   * ConnectionClass until another timer is started.
   */
  public void stopSampling() {
    if (mSamplingCounter.decrementAndGet() == 0) {
      mHandler.stopSamplingThread();
      addFinalSample();
    }
  }

  /**
   * Method for polling for the change in total bytes since last update and
   * adding it to the BandwidthManager.
   */
  protected void addSample() {
      //TrafficStats是android用于计算开机到现在所使用的流量
      //先返回当前开机到现在所使用的流量总数
      long newBytes = TrafficStats.getTotalRxBytes();
      //与上次测量的流量总数做差，这样可以获得间隔中所使用的的流量总数
      long byteDiff = newBytes - sPreviousBytes;
      if (sPreviousBytes >= 0) {
          synchronized (this) {
              //获取当前开机到现在过去的毫秒数
              long curTimeReading = SystemClock.elapsedRealtime();
              //这里就是实际处理变化和计算的逻辑
              mConnectionClassManager.addBandwidth(byteDiff, curTimeReading - mLastTimeReading);

              mLastTimeReading = curTimeReading;
          }
      }
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
                    addSample();
                    //1s发送一条信号，相当于轮询，时间间隔为1s
                    sendEmptyMessageDelayed(MSG_START, SAMPLE_TIME);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown what=" + msg.what);
            }
        }

        /**
         * 发送开始信号
         */
        public void startSamplingThread() {
            sendEmptyMessage(SamplingHandler.MSG_START);
        }

        public void stopSamplingThread() {
            removeMessages(SamplingHandler.MSG_START);
        }
    }
}