package com.sportvenue.provider;

import com.sportvenue.dto.response.LocationDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.boot.web.client.RestTemplateBuilder;
import java.time.Duration;

@Service
@Slf4j
public class GoongGeocodeProvider implements GeocodeProvider {
    
    @Value("${goong.api.key}")
    private String apiKey;
    
    private final RestTemplate restTemplate;

    public GoongGeocodeProvider(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public List<LocationDTO> search(String query) {
        try {
            String url = "https://rsapi.goong.io/geocode?address={address}&api_key={api_key}";
            log.info("Goong search request: query={}", query);
            Map<String, Object> response = restTemplate.getForObject(url, Map.class, query, apiKey);
            log.info("Goong search raw response: {}", response);
            if (response != null && response.containsKey("results")) {
                Object resultsRaw = response.get("results");
                log.info("Goong search results type: {}, value: {}", resultsRaw == null ? "null" : resultsRaw.getClass().getName(), resultsRaw);
                if (resultsRaw == null) {
                    return Collections.emptyList();
                }
                List<Map<String, Object>> results = (List<Map<String, Object>>) resultsRaw;
                List<LocationDTO> locations = new ArrayList<>();
                for (Map<String, Object> result : results) {
                    Map<String, Object> geometry = (Map<String, Object>) result.get("geometry");
                    Map<String, Object> location = (Map<String, Object>) geometry.get("location");

                    LocationDTO dto = LocationDTO.builder()
                            .displayName((String) result.get("formatted_address"))
                            .latitude(Double.valueOf(location.get("lat").toString()))
                            .longitude(Double.valueOf(location.get("lng").toString()))
                            .source("GOONG")
                            .build();

                    fillAddressComponents(dto, (List<Map<String, Object>>) result.get("address_components"));
                    locations.add(dto);
                }
                return locations;
            }
            log.warn("Goong search: no 'results' key in response for query={}", query);
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Goong search error for query {}: {}", query, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    @Override
    public LocationDTO reverseGeocode(Double lat, Double lng) {
        try {
            String url = "https://rsapi.goong.io/Geocode?latlng={latlng}&api_key={api_key}";
            
            Map<String, Object> response = restTemplate.getForObject(url, Map.class, lat + "," + lng, apiKey);
            if (response != null && response.containsKey("results")) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
                if (!results.isEmpty()) {
                    Map<String, Object> result = results.get(0);
                    LocationDTO dto = LocationDTO.builder()
                            .displayName((String) result.get("formatted_address"))
                            .latitude(lat)
                            .longitude(lng)
                            .source("GOONG")
                            .build();
                    
                    fillAddressComponents(dto, (List<Map<String, Object>>) result.get("address_components"));
                    return dto;
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Goong reverse geocode error for {},{}: {}", lat, lng, e.getMessage());
            return null;
        }
    }

    private void fillAddressComponents(LocationDTO dto, List<Map<String, Object>> components) {
        if (components == null) {
            return;
        }
        
        for (Map<String, Object> component : components) {
            List<String> types = (List<String>) component.get("types");
            if (types == null) {
                continue;
            }
            
            String longName = (String) component.get("long_name");
            
            if (types.contains("administrative_area_level_1")) {
                dto.setProvince(longName);
            } else if (types.contains("administrative_area_level_2")) {
                dto.setDistrict(longName);
            } else if (types.contains("ward")) {
                dto.setWard(longName);
            } else if (types.contains("route")) {
                dto.setStreet(longName);
            }
        }
    }
}
