package com.winter.airesumeoptimizer.module.workspace.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RewriteMaterialityGateImplTest {

    private final RewriteMaterialityGateImpl gate = new RewriteMaterialityGateImpl();

    @Test
    void rejectsIdenticalTextAfterNfkcAndTrim() {
        assertThat(gate.isLowValueChange("  设计统一订单状态机  ", "设计统一订单状态机")).isTrue();
    }

    @Test
    void rejectsPunctuationOnlyChange() {
        assertThat(gate.isLowValueChange(
                "负责订单系统开发，维护接口",
                "负责订单系统开发；维护接口")).isTrue();
    }

    @Test
    void rejectsConnectorOnlyChange() {
        assertThat(gate.isLowValueChange(
                "设计统一订单状态机，落地分布式事务方案",
                "设计统一订单状态机，并落地分布式事务方案")).isTrue();
    }

    @Test
    void rejectsFunctionWordOnlyChange() {
        assertThat(gate.isLowValueChange(
                "负责订单服务开发",
                "负责了订单服务的开发")).isTrue();
    }

    @Test
    void allowsConcreteSynonymRewrite() {
        assertThat(gate.isLowValueChange(
                "负责订单服务后端接口开发",
                "承担订单服务后端接口的开发工作")).isFalse();
    }

    @Test
    void allowsRealFactPreservingReordering() {
        assertThat(gate.isLowValueChange(
                "使用 Redis 实现缓存功能，参与订单模块开发",
                "参与订单模块开发，并使用 Redis 实现缓存功能")).isFalse();
    }
}
