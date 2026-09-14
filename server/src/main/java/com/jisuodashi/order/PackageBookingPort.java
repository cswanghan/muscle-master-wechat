package com.jisuodashi.order;

/**
 * 下单时把课包绑到订单上。由会员域实现 —— order 是下层，只声明口，不认识课包。
 */
public interface PackageBookingPort {

    /**
     * 校验这张课包能用（属于该顾客、有余次、没过期）并绑到订单上。
     *
     * <p>只绑不推状态：推 BOOKED 由 order 层自己做，否则会员域要反向持有状态机，
     * 构造期就循环了。
     *
     * @throws com.jisuodashi.common.ApiException 课包不可用时抛，此时订单已锁但未支付，
     *         走正常的超时释放。
     */
    void bindToOrder(long customerId, long orderId, String memberPackageId);
}
