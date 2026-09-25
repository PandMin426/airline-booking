package com.airlinebooking.booking.service.imp;

import com.airlinebooking.booking.entity.BaggageCatalogEntity;
import com.airlinebooking.booking.mapper.BaggageMapper;
import com.airlinebooking.booking.payload.response.BaggageResponse;
import com.airlinebooking.booking.repository.BaggageCatalogRepository;
import com.airlinebooking.booking.service.BaggageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Service
public class BaggageServiceImp implements BaggageService {

    private final BaggageCatalogRepository baggageCatalogRepository;

    private final BaggageMapper baggageMapper;

    @Override
    public List<BaggageResponse> getAllBaggages() {
        List<BaggageResponse> baggageResponseList = new ArrayList<>();

        List<BaggageCatalogEntity> baggageCatalogEntityList = new ArrayList<>();

        baggageCatalogEntityList = baggageCatalogRepository.findAll();

        for(BaggageCatalogEntity b : baggageCatalogEntityList){
            BaggageResponse baggageResponse = new BaggageResponse();
            baggageResponse = baggageMapper.toResponse(b);

            baggageResponseList.add(baggageResponse);
        }


        return baggageResponseList;
    }
}
