package com.github.yingli.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.yingli.entity.MemberInfo;
import com.github.yingli.mapper.MemberInfoMapper;
import com.github.yingli.service.MemberInfoService;
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
