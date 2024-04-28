package com.dp.controller;


import com.dp.model.dto.Result;
import com.dp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        //List<ShopType> typeList = typeService
        //        .query().orderByAsc("sort").list();
        // 店铺类型查询缓存

        return typeService.queryType();
    }
}
