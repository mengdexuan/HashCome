package com.bizzan.bitrade.job;

import com.bizzan.bitrade.entity.MemberRecord;
import com.bizzan.bitrade.service.MemberRecordService;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Country;
import com.maxmind.geoip2.record.Location;
import com.maxmind.geoip2.record.Subdivision;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 解析IP地址信息
 * @Author: bigdogex.com
 * @Date: 2021-01-20 12:38
 */
@Component
@Slf4j
public class AnalysisMemberRecord {

    @Autowired
    private MemberRecordService memberRecordService;
}
