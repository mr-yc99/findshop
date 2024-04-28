package com.dp.service;

import com.dp.model.dto.Result;
import com.dp.model.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result secKillVoucherOrder(Long voucherId);

    //Result createVoucherOrder(Long voucherId, Long userId, Integer stock);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
