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

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>
 * Class used to calculate the approximate bandwidth of a user's connection.
 * 用于计算用户连接的大概带宽
 * </p>
 * <p>
 * This class notifies all subscribed {@link ConnectionClassStateChangeListener} with the new
 * ConnectionClass when the network's ConnectionClass changes.
 * </p>
 */
public class ConnectionClassManager {

  /*package*/
  //在检测带宽变化的时候，因为有的时候可能因为波动等原因导致过于短暂的变化
  //检测时候是采用一定时间自动检测，那么就需要定义一个基础的检测次数
  //用于规定什么时候带宽值的变化可以认为有效
  static final double DEFAULT_SAMPLES_TO_QUALITY_CHANGE = 5;
  //这个就是一个字节有8位的意思
  private static final int BYTES_TO_BITS = 8;

  /**
   * Default values for determining quality of data connection.
   * Bandwidth numbers are in Kilobits per second (kbps).
   */
  /*package*/ static final int DEFAULT_POOR_BANDWIDTH = 150;
  /*package*/ static final int DEFAULT_MODERATE_BANDWIDTH = 550;
  /*package*/ static final int DEFAULT_GOOD_BANDWIDTH = 2000;
  /*package*/ static final long DEFAULT_HYSTERESIS_PERCENT = 20;
  private static final double HYSTERESIS_TOP_MULTIPLIER = 100.0 / (100.0 - DEFAULT_HYSTERESIS_PERCENT);
  private static final double HYSTERESIS_BOTTOM_MULTIPLIER = (100.0 - DEFAULT_HYSTERESIS_PERCENT) / 100.0;

  /**
   * The factor used to calculate the current bandwidth
   * depending upon the previous calculated value for bandwidth.
   *
   * The smaller this value is, the less responsive to new samples the moving average becomes.
   */
  private static final double DEFAULT_DECAY_CONSTANT = 0.05;

  /**
   * 实际进行当前带宽多大的计算器
   * 内部有存储当前带宽大小
   * */
  private ExponentialGeometricAverage mDownloadBandwidth
      = new ExponentialGeometricAverage(DEFAULT_DECAY_CONSTANT);
  //用于标记当前带宽是否发生变化
  private volatile boolean mInitiateStateChange = false;
  //下面很多都是原子操作，简单的理解就是不用考虑多线程的问题
  //当前网络连接带宽的质量，具体看ConnectionQuality里面定义的参数
  private AtomicReference<ConnectionQuality> mCurrentBandwidthConnectionQuality =
      new AtomicReference<ConnectionQuality>(ConnectionQuality.UNKNOWN);
  private AtomicReference<ConnectionQuality> mNextBandwidthConnectionQuality;
  private ArrayList<ConnectionClassStateChangeListener> mListenerList =
      new ArrayList<ConnectionClassStateChangeListener>();
  private int mSampleCounter;

  /**
   * The lower bound for measured bandwidth in bits/ms. Readings
   * lower than this are treated as effectively zero (therefore ignored).
   * 测量的时候可以接受的在当前测量间隔内收到的最小字节位数
   */
  static final long BANDWIDTH_LOWER_BOUND = 10;

  // Singleton.
  //静态内部类的单例实现模式，这种不需要考虑线程同步以及同步的多余开销
  private static class ConnectionClassManagerHolder {
      public static final ConnectionClassManager instance = new ConnectionClassManager();
  }

  /**
   * Retrieval method for the DownloadBandwidthManager singleton.
   * @return The singleton instance of DownloadBandwidthManager.
   */
  @Nonnull
  public static ConnectionClassManager getInstance() {
      return ConnectionClassManagerHolder.instance;
  }

  // Force constructor to be private.
  private ConnectionClassManager() {}

  /**
   * Adds bandwidth to the current filtered latency counter. Sends a broadcast to all
   * {@link ConnectionClassStateChangeListener} if the counter moves from one bucket
   * to another (i.e. poor bandwidth -> moderate bandwidth).
   * @param bytes timeInMs这段时间内所收到的字节数
   * @param timeInMs 计算的时间
   */
  public synchronized void addBandwidth(long bytes, long timeInMs) {

    //1.当前计算时间必须>0
    //2.当前间隔内所收到的包的字节数的位数必须大于预定义的最小值，默认10
    if (timeInMs == 0 || (bytes) * 1.0 / (timeInMs) * BYTES_TO_BITS < BANDWIDTH_LOWER_BOUND) {
      return;
    }
    //获得当前每毫秒所收到的字节位数
    double bandwidth = (bytes) * 1.0 / (timeInMs) * BYTES_TO_BITS;
    //将当前数据传入计算器中进行计算，后续计算结果会保留在计算器中
    mDownloadBandwidth.addMeasurement(bandwidth);
    if (mInitiateStateChange) {//当前带宽发生变化
      mSampleCounter += 1;//带宽变化采样次数+1
      //之前带宽变化的时候记录了带宽等级
      //如果这次采样的时候带宽等级再一次发生变化
      if (getCurrentBandwidthQuality() != mNextBandwidthConnectionQuality.get()) {
        //还原数据，等待之后的采样，因为认为当前是带宽波动，之前的计算无效
        mInitiateStateChange = false;
        mSampleCounter = 1;
      }
      //1.至少要保持5次相同的带宽状态才认为这种状态是处于稳定的状况，否则可能存在偶然的情况，一般来说测量时间为1s的话，则这种稳定范围任务是5s
      //2.进行状态变动回调的时候有一个最小变化大小范围
      // 默认如果是变大，要求超过原来最大值 * 1.25
      // 如果变小，要求至少小于等于原来的80%
      if (mSampleCounter >= DEFAULT_SAMPLES_TO_QUALITY_CHANGE  && significantlyOutsideCurrentBand()) {
        //还原标记
        mInitiateStateChange = false;
        mSampleCounter = 1;
        //修改当前带宽状态
        mCurrentBandwidthConnectionQuality.set(mNextBandwidthConnectionQuality.get());
        //通知观察者带宽发生变化
        notifyListeners();
      }
      return;
    }
    //如果当前带宽状态发生了变化
    if (mCurrentBandwidthConnectionQuality.get() != getCurrentBandwidthQuality()) {
      //标记状态改变
      mInitiateStateChange = true;
      //记录下一个带宽状态
      mNextBandwidthConnectionQuality =
          new AtomicReference<ConnectionQuality>(getCurrentBandwidthQuality());
    }
  }

  /**
   * Get the ConnectionQuality that the moving bandwidth average currently represents.
   * 通过计算器中计算的结果得到当前带宽等级
   * @return A ConnectionQuality representing the device's bandwidth at this exact moment.
   */
  public synchronized ConnectionQuality getCurrentBandwidthQuality() {
    if (mDownloadBandwidth == null) {
      return ConnectionQuality.UNKNOWN;
    }
    return mapBandwidthQuality(mDownloadBandwidth.getAverage());
  }

  /**
   * 校验变化的正确性和确立变化的范围
   * @return true认为是有效的变化
   */
  private boolean  significantlyOutsideCurrentBand() {
    if (mDownloadBandwidth == null) {
      // Make Infer happy. It wouldn't make any sense to call this while mDownloadBandwidth is null.
      return false;
    }
    ConnectionQuality currentQuality = mCurrentBandwidthConnectionQuality.get();
    double bottomOfBand;
    double topOfBand;
    switch (currentQuality) {
      case POOR:
        bottomOfBand = 0;
        topOfBand = DEFAULT_POOR_BANDWIDTH;
        break;
      case MODERATE:
        bottomOfBand = DEFAULT_POOR_BANDWIDTH;
        topOfBand = DEFAULT_MODERATE_BANDWIDTH;
        break;
      case GOOD:
        bottomOfBand = DEFAULT_MODERATE_BANDWIDTH;
        topOfBand = DEFAULT_GOOD_BANDWIDTH;
        break;
      case EXCELLENT:
        bottomOfBand = DEFAULT_GOOD_BANDWIDTH;
        topOfBand = Float.MAX_VALUE;
        break;
      default: // If current quality is UNKNOWN, then changing is always valid.
        return true;
    }
    double average = mDownloadBandwidth.getAverage();
    //简单说就是如果当前带宽变高了，那么至少也要比之前高25个百分比，低的话至少低20个百分比
    if (average > topOfBand) {
      if (average > topOfBand * HYSTERESIS_TOP_MULTIPLIER) {
        return true;
      }
    } else if (average < bottomOfBand * HYSTERESIS_BOTTOM_MULTIPLIER) {
      return true;
    }
    return false;
  }

  /**
   * 根据当前带宽的平均值进行映射
   * 然后返回预定义的带宽等级
   * @param average 当前带宽的平均值
   * @return 当前带宽的预定义等级
   */
  private ConnectionQuality mapBandwidthQuality(double average) {
    //这个定义实际上看ConnectionQuality也明白
    if (average < 0) {
      return ConnectionQuality.UNKNOWN;
    }
    if (average < DEFAULT_POOR_BANDWIDTH) {
      return ConnectionQuality.POOR;
    }
    if (average < DEFAULT_MODERATE_BANDWIDTH) {
      return ConnectionQuality.MODERATE;
    }
    if (average < DEFAULT_GOOD_BANDWIDTH) {
      return ConnectionQuality.GOOD;
    }
    return ConnectionQuality.EXCELLENT;
  }

  /**
   * Interface for listening to when {@link com.facebook.network.connectionclass.ConnectionClassManager}
   * changes state.
   * 接口用于监听连接状态的改变
   */
  public interface ConnectionClassStateChangeListener {
    /**
     * The method that will be called when {@link com.facebook.network.connectionclass.ConnectionClassManager}
     * changes ConnectionClass.
     * @param bandwidthState The new ConnectionClass.
     */
    public void onBandwidthStateChange(ConnectionQuality bandwidthState);
  }

  /**
   * Method for adding new listeners to this class.
   * 添加监听用于在网络状态变化的时候进行处理
   * @param listener {@link ConnectionClassStateChangeListener} to add as a listener.
   */
  public ConnectionQuality register(ConnectionClassStateChangeListener listener) {
    if (listener != null) {
      mListenerList.add(listener);
    }
    return mCurrentBandwidthConnectionQuality.get();
  }

  /**
   * Method for removing listeners from this class.
   * 移除指定的网络状态变化监听
   * @param listener Reference to the {@link ConnectionClassStateChangeListener} to be removed.
   */
  public void remove(ConnectionClassStateChangeListener listener) {
    if (listener != null) {
      mListenerList.remove(listener);
    }
  }

  /**
   * 通知所有的观察者进行回调
   */
  private void notifyListeners() {
    int size = mListenerList.size();
    for (int i = 0; i < size; i++) {
      mListenerList.get(i).onBandwidthStateChange(mCurrentBandwidthConnectionQuality.get());
    }
  }

  /**
   * Accessor method for the current bandwidth average.
   * @return The current bandwidth average, or -1 if no average has been recorded.
   */
  public synchronized double getDownloadKBitsPerSecond() {
    return mDownloadBandwidth == null
            ? -1.0
            : mDownloadBandwidth.getAverage();
  }


  /**
   * Resets the bandwidth average for this instance of the bandwidth manager.
   */
  public void reset() {
    if (mDownloadBandwidth != null) {
      mDownloadBandwidth.reset();
    }
    mCurrentBandwidthConnectionQuality.set(ConnectionQuality.UNKNOWN);
  }
}
