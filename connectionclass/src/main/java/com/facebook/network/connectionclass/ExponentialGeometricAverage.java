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
    //这里都是在计算有效值，因为在几次的计算中肯定是有浮动的，如何取一个有效值就非常的重要
    //注意这里有计算一个偏移量，简单理解就是平均值应该倾向之前计算的平均值，但是也有部分靠近当前计算的值
    if (mCount > mCutover) {
      mValue = Math.exp(keepConstant * Math.log(mValue) + mDecayConstant * Math.log(measurement));
    } else if (mCount > 0) {
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
