package com.itzixi.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.itzixi.entity.MemberInfo;
import com.itzixi.mapper.MemberInfoMapper;
import com.itzixi.service.MemberInfoService;
import org.springframework.stereotype.Service;

/**
 * MemberInfoServiceImpl — MyBatis Plus ServiceImpl（继承 ServiceImpl，提供默认 CRUD 实现）。
 *
 * @author oliver
 */
@Service
public class MemberInfoServiceImpl
        extends ServiceImpl<MemberInfoMapper, MemberInfo>
        implements MemberInfoService {
}
