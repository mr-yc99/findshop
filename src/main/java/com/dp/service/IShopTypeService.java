package com.dp.service;

import com.dp.model.dto.Result;
import com.dp.model.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
public interface IShopTypeService extends IService<ShopType> {

    Result queryType();
}
