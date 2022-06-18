package com.bizzan.bitrade.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.bizzan.bitrade.dao.CountryDao;
import com.bizzan.bitrade.entity.Country;

import java.util.List;

/**
 * @author Jammy
 * @date 2019年02月10日
 */
@Service
public class CountryService {
    @Autowired
    private CountryDao countryDao;

    public List<Country> getAllCountry(){
        return countryDao.findAllOrderBySort();
    }

    public Country findOne(String zhName){
        return countryDao.findByZhName(zhName);
    }

    public List<Country> getAllRegCountry() {
        return countryDao.findAllByIsSupportReg(1);
    }
}
