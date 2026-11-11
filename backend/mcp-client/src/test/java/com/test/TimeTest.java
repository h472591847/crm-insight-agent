package com.test;


import cn.hutool.core.date.StopWatch;
import com.itzixi.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Time;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName TimeTest
 * @Description
 * @Author oliver
 * @Date 2026/7/16 14:19
 */
@SpringBootTest(classes = Application.class)
public class TimeTest {
    @Test
    public void testTime() throws InterruptedException {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start("任务1");
        Thread.sleep(1000);
        stopWatch.stop();

        stopWatch.start("任务2");
        Thread.sleep(300);
        stopWatch.stop();

        stopWatch.start("任务3");
        Thread.sleep(100);
        stopWatch.stop();
        //打印任务耗时
        System.out.println(stopWatch.prettyPrint());
        System.out.println(stopWatch.shortSummary());
        // 任务总览
        System.out.println("所有任务总耗时:" + stopWatch.getTotalTimeSeconds() + "秒");
        System.out.println("任务总数:" + stopWatch.getTaskCount());

    }
}
