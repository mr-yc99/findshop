package com.dp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.dp.model.dto.Result;
import com.dp.model.dto.ShopTypeDTO;
import com.dp.model.entity.ShopType;
import com.dp.mapper.ShopTypeMapper;
import com.dp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author forya
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryType() {
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY;
        //存放返回给前端的店铺类型列表
        ArrayList<ShopTypeDTO> typeList = new ArrayList<>();

        // 先在redis中查询，有 -> 先转换成List列表（此时list中的元素是字符，要序列化） -> 返回给前端
        List<String> typeStrList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //转换成List列表
        if(typeStrList != null && !typeStrList.isEmpty()) {
            for (String typeStr : typeStrList) {
                ShopTypeDTO type = JSONUtil.toBean(typeStr, ShopTypeDTO.class);
                typeList.add(type);
            }
            return Result.ok(typeList);
        }

        // redis中没有，先从数据库中取出，并以list类型存储到redis中 -> 先转换成List列表 -> 返回给前端
        List<ShopType> shopTypes = this.query().orderByAsc("sort").list();
        //先装换成DTO
        for (ShopType shopType : shopTypes) {
            typeList.add(BeanUtil.copyProperties(shopType, ShopTypeDTO.class));
        }

        // 存到redis
        for (ShopTypeDTO shopTypeDTO : typeList) {
            //先序列化成Json
            String typeJsonStr = JSONUtil.toJsonStr(shopTypeDTO);
            stringRedisTemplate.opsForList().rightPush(key, typeJsonStr);
        }

        return Result.ok(typeList);
    }
}
