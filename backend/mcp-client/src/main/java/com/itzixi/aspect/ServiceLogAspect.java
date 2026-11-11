package com.itzixi.aspect;


import cn.hutool.core.date.StopWatch;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * @Author oliver
 */
@Component
@Slf4j
@Aspect
public class ServiceLogAspect {
    /**
     * AOP环绕切面
     * * 返回任意类型,void， 也可以是其他类型的参数
     * com.itzixi.service.impl 指定包名， 要切的class的所在包
     * .. 可以匹配到当前包和子包中的类
     * * 匹配当前包以及子包下的class类
     * . 无意义
     * * 匹配任意方法名
     * (..) 方法的参数, 匹配任意参数
     *
     * @param joinPoint
     * @return
     * @throws Throwable
     */
    @Around("execution(* com.itzixi.service.impl..*.*(..))")
    public Object recordTimesLog(ProceedingJoinPoint joinPoint) throws Throwable {
//        long beginTime = System.currentTimeMillis();
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        Object proceed = joinPoint.proceed();
        String point = joinPoint.getTarget().getClass().getName()
                + "."
                + joinPoint.getSignature().getName();

//        long endTime = System.currentTimeMillis();
//        long takeTime = endTime - beginTime;
        stopWatch.stop();
        long takeTime = stopWatch.getTotalTimeMillis();
        if (takeTime > 30000) {
            log.error("{}耗时过长 {}毫秒", point, takeTime);
        }  else {
            log.info("{}耗时 {}毫秒", point, takeTime);
        }
        return proceed;
    }
}
