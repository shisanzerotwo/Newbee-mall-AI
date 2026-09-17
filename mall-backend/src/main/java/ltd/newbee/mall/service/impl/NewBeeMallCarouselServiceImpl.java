/**
 * 严肃声明：
 * 开源版本请务必保留此注释头信息，若删除我方将保留所有法律责任追究！
 * 本系统已申请软件著作权，受国家版权局知识产权以及国家计算机软件著作权保护！
 * 可正常分享和学习源码，不得用于违法犯罪活动，违者必究！
 * Copyright (c) 2019-2020 十三 all rights reserved.
 * 版权所有，侵权必究！
 */
package ltd.newbee.mall.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import ltd.newbee.mall.common.Constants;
import ltd.newbee.mall.common.ServiceResultEnum;
import ltd.newbee.mall.controller.vo.NewBeeMallIndexCarouselVO;
import ltd.newbee.mall.dao.CarouselMapper;
import ltd.newbee.mall.entity.Carousel;
import ltd.newbee.mall.service.NewBeeMallCarouselService;
import ltd.newbee.mall.util.BeanUtil;
import ltd.newbee.mall.util.PageQueryUtil;
import ltd.newbee.mall.util.PageResult;
import ltd.newbee.mall.util.RedisCacheUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class NewBeeMallCarouselServiceImpl implements NewBeeMallCarouselService {

    @Autowired
    private CarouselMapper carouselMapper;

    @Autowired
    private RedisCacheUtil redisCacheUtil;

    @Override
    public PageResult getCarouselPage(PageQueryUtil pageUtil) {
        List<Carousel> carousels = carouselMapper.findCarouselList(pageUtil);
        int total = carouselMapper.getTotalCarousels(pageUtil);
        PageResult pageResult = new PageResult(carousels, total, pageUtil.getLimit(), pageUtil.getPage());
        return pageResult;
    }

    @Override
    public String saveCarousel(Carousel carousel) {
        if (carouselMapper.insertSelective(carousel) > 0) {
            // 后台修改轮播图后,删除首页缓存,下次访问首页时重新查库
            redisCacheUtil.delete(Constants.INDEX_CAROUSEL_CACHE_KEY);
            return ServiceResultEnum.SUCCESS.getResult();
        }
        return ServiceResultEnum.DB_ERROR.getResult();
    }

    @Override
    public String updateCarousel(Carousel carousel) {
        Carousel temp = carouselMapper.selectByPrimaryKey(carousel.getCarouselId());
        if (temp == null) {
            return ServiceResultEnum.DATA_NOT_EXIST.getResult();
        }
        temp.setCarouselRank(carousel.getCarouselRank());
        temp.setRedirectUrl(carousel.getRedirectUrl());
        temp.setCarouselUrl(carousel.getCarouselUrl());
        temp.setUpdateTime(new Date());
        if (carouselMapper.updateByPrimaryKeySelective(temp) > 0) {
            // 后台修改轮播图后,删除首页缓存
            redisCacheUtil.delete(Constants.INDEX_CAROUSEL_CACHE_KEY);
            return ServiceResultEnum.SUCCESS.getResult();
        }
        return ServiceResultEnum.DB_ERROR.getResult();
    }

    @Override
    public Carousel getCarouselById(Integer id) {
        return carouselMapper.selectByPrimaryKey(id);
    }

    @Override
    public Boolean deleteBatch(Integer[] ids) {
        if (ids.length < 1) {
            return false;
        }
        //删除数据
        if (carouselMapper.deleteBatch(ids) > 0) {
            // 后台删除轮播图后,删除首页缓存
            redisCacheUtil.delete(Constants.INDEX_CAROUSEL_CACHE_KEY);
            return true;
        }
        return false;
    }

    @Override
    public List<NewBeeMallIndexCarouselVO> getCarouselsForIndex(int number) {
        // 先从 Redis 缓存读取,命中则直接返回,避免查库
        List<NewBeeMallIndexCarouselVO> cacheList = redisCacheUtil.get(
                Constants.INDEX_CAROUSEL_CACHE_KEY,
                new TypeReference<List<NewBeeMallIndexCarouselVO>>() {
                });
        if (!CollectionUtils.isEmpty(cacheList)) {
            return cacheList;
        }
        // 缓存未命中:查数据库并写入缓存
        List<NewBeeMallIndexCarouselVO> newBeeMallIndexCarouselVOS = new ArrayList<>(number);
        List<Carousel> carousels = carouselMapper.findCarouselsByNum(number);
        if (!CollectionUtils.isEmpty(carousels)) {
            newBeeMallIndexCarouselVOS = BeanUtil.copyList(carousels, NewBeeMallIndexCarouselVO.class);
            redisCacheUtil.set(Constants.INDEX_CAROUSEL_CACHE_KEY, newBeeMallIndexCarouselVOS, Constants.INDEX_CACHE_TTL);
        }
        return newBeeMallIndexCarouselVOS;
    }
}
