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

  /*package*/ static final double DEFAULT_SAMPLES_TO_QUALITY_CHANGE = 5;
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

  /** Current bandwidth of the user's connection depending upon the response. */
  private ExponentialGeometricAverage mDownloadBandwidth
      = new ExponentialGeometricAverage(DEFAULT_DECAY_CONSTANT);
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
   * @param bytes 流量差值
   * @param timeInMs 这些流量差值计算的时间
   */
  public synchronized void addBandwidth(long bytes, long timeInMs) {

    //Ignore garbage values.
    //这些单位都要转为KB
    //过滤无效数据
    if (timeInMs == 0 || (bytes) * 1.0 / (timeInMs) * BYTES_TO_BITS < BANDWIDTH_LOWER_BOUND) {
      return;
    }
    //当前所使用的的带宽kbps
    double bandwidth = (bytes) * 1.0 / (timeInMs) * BYTES_TO_BITS;
    //计算平均带宽并记录
    mDownloadBandwidth.addMeasurement(bandwidth);
    //初始化此处都为false，主要用于过滤一开始从0到有的情况，这种时候带宽状态发生了变化是正常的，不应该进行回调
    if (mInitiateStateChange) {
      mSampleCounter += 1;
      //当前带宽发生了变化，还原一些标记
      if (getCurrentBandwidthQuality() != mNextBandwidthConnectionQuality.get()) {
        mInitiateStateChange = false;
        mSampleCounter = 1;
      }
      //至少要保持5次相同的带宽状态才认为这种状态是处于稳定的状况，否则可能存在偶然的情况，一般来说测量时间为1s的话，则这种稳定范围任务是5s
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
   * Resets the bandwidth average for this instance of the bandwidth manager.
   */
  public void reset() {
    if (mDownloadBandwidth != null) {
      mDownloadBandwidth.reset();
    }
    mCurrentBandwidthConnectionQuality.set(ConnectionQuality.UNKNOWN);
  }

  /**
   * Get the ConnectionQuality that the moving bandwidth average currently represents.
   * @return A ConnectionQuality representing the device's bandwidth at this exact moment.
   */
  public synchronized ConnectionQuality getCurrentBandwidthQuality() {
    if (mDownloadBandwidth == null) {
      return ConnectionQuality.UNKNOWN;
    }
    return mapBandwidthQuality(mDownloadBandwidth.getAverage());
  }

  private ConnectionQuality mapBandwidthQuality(double average) {
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
   * Accessor method for the current bandwidth average.
   * @return The current bandwidth average, or -1 if no average has been recorded.
   */
  public synchronized double getDownloadKBitsPerSecond() {
    return mDownloadBandwidth == null
        ? -1.0
        : mDownloadBandwidth.getAverage();
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
}