/**
 * 设计 §Observability 指标表的落地：刮取型 gauge（{@link com.jisuodashi.observability.BusinessMetrics}）、
 * 支付成功率滚动窗口（{@link com.jisuodashi.observability.PayOutcomeMetrics}）、
 * 释放扫描心跳（{@link com.jisuodashi.observability.ReleaseScanHeartbeat}）。
 * 只读，不参与任何事务；计数器留在产生它们的模块里。
 */
package com.jisuodashi.observability;
