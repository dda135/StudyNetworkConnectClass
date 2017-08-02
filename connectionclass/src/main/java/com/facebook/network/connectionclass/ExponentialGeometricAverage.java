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

/**
 * Moving average calculation for ConnectionClass.
 */
class ExponentialGeometricAverage {
  //默认为0.05
  private final double mDecayConstant;
  //这里应该认为是20
  private final int mCutover;

  private double mValue = -1;
  private int mCount;

  public ExponentialGeometricAverage(double decayConstant) {
    mDecayConstant = decayConstant;
    mCutover = decayConstant == 0.0
        ? Integer.MAX_VALUE
        : (int) Math.ceil(1 / decayConstant);
  }

  /**
   * Adds a new measurement to the moving average.
   * @param measurement - Bandwidth measurement in bits/ms to add to the moving average.
   */
  public void addMeasurement(double measurement) {
    //0.95
    double keepConstant = 1 - mDecayConstant;
    //因为在确信带宽状态稳定的情况下会进行多次计算，这里在确定这一段时间内的带宽平均大小
    //注意这里有计算一个偏移量keepConstant，这个可以在ConnectionClassManager中定义
    //这里在计算的时候没有直接均分，而是采用了比例
    //直观地理解就是计算的次数越多，之前计算的结果占比就越大，新的带宽大小占比就低
    //这个是用于计算在整个采样过程中的带宽大小，那么旧的结果占比大是正常的
    if (mCount > mCutover) {
      mValue = Math.exp(keepConstant * Math.log(mValue) + mDecayConstant * Math.log(measurement));
    } else if (mCount > 0) {
      //keepConstant - （keepConstant）/(mCount + 1.0)
      //mCount越大retained越大
      double retained = keepConstant * mCount / (mCount + 1.0);
      double newcomer = 1.0 - retained;
      mValue = Math.exp(retained * Math.log(mValue) + newcomer * Math.log(measurement));
    } else {//初始化count==0
      mValue = measurement;
    }
    //注意这个如果不手动reset的话，是会一直进行累加
    mCount++;
  }

  public double getAverage() {
    return mValue;
  }

  /**
   * Reset the moving average.
   */
  public void reset() {
    mValue = -1.0;
    mCount = 0;
  }
}
