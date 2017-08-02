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
 * A general enumeration for different connection qualities.
 * 定义了一些质量，具体的值在ConnectionClassManager里面有定义
 *
 * <p>
 * In order to compare qualities use the {@link .compareTo()} method. Qualities are ordered in increasing
 * order of declaration as per the java docs for {@link java.lang.Enum}.
 * 为了通过compareTo方法来进行带宽质量的比较，带宽质量的定义按照增长的顺序定义
 * 因为Enum默认的compareTo会比较ordinal，而这个按照定义的顺序自增长
 * </p>
 */
public enum ConnectionQuality {
  /**
   * Bandwidth under 150 kbps.
   * 当前带宽在1.5m以下
   */
  POOR,
  /**
   * Bandwidth between 150 and 550 kbps.
   * 当前带宽在1.5m和5.5m之间
   */
  MODERATE,
  /**
   * Bandwidth between 550 and 2000 kbps.
   * 当前带宽在5.5m和20m之间
   */
  GOOD,
  /**
   * EXCELLENT - Bandwidth over 2000 kbps.
   * 当前带宽在20m以上
   */
  EXCELLENT,
  /**
   * Placeholder for unknown bandwidth. This is the initial value and will stay at this value
   * if a bandwidth cannot be accurately found.
   * 初始值，或者说当前计算带宽还未得到结果
   */
  UNKNOWN
}
