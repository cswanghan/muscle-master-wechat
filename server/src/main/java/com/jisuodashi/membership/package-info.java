/**
 * 会员制课包：会员档案、课包余次、课时流水、训练计划。
 *
 * <p>与 {@code card}（储值卡）并存而非替代：储值卡按**金额**抵扣，课包按**次数**扣。
 * 两者的退法、有效期、是否绑定课程类型都不同，合成一个模型会把三件事都挤掉。
 *
 * <p>本包的核心口径：**卖课是负债，耗课才是收入**。课包卖出去时钱进了账但课还没上，
 * 所以老师的销售业绩与耗课业绩分别统计，见 {@link com.jisuodashi.membership.MembershipPolicy}。
 */
package com.jisuodashi.membership;
