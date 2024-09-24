package com.sparrowwallet.sparrow.net;

import com.sparrowwallet.sparrow.AppServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public enum FeeRatesSource {
    ELECTRUM_SERVER("Server", false) {
        @Override
        public Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates) {
            return Collections.emptyMap();
        }
    },
    MEDIUM("Medium (20000 sat/vB)", true) {
        @Override
        public Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates) {
            Map<Integer, Double> blockTargetFeeRates = new LinkedHashMap<>();
            for(Integer blockTarget : defaultblockTargetFeeRates.keySet()) {
                blockTargetFeeRates.put(blockTarget, 20000.0);
            }

            return blockTargetFeeRates;
        }
    },
    MINIMUM("Minimum (10000 sat/vB)", false) {
        @Override
        public Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates) {
            Map<Integer, Double> blockTargetFeeRates = new LinkedHashMap<>();
            for(Integer blockTarget : defaultblockTargetFeeRates.keySet()) {
                blockTargetFeeRates.put(blockTarget, 10000.0);
            }

            return blockTargetFeeRates;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(FeeRatesSource.class);
    public static final int BLOCKS_IN_HALF_HOUR = 3;
    public static final int BLOCKS_IN_HOUR = 6;
    public static final int BLOCKS_IN_TWO_HOURS = 12;

    private final String name;
    private final boolean external;

    FeeRatesSource(String name, boolean external) {
        this.name = name;
        this.external = external;
    }

    public abstract Map<Integer, Double> getBlockTargetFeeRates(Map<Integer, Double> defaultblockTargetFeeRates);

    public String getName() {
        return name;
    }

    public boolean isExternal() {
        return external;
    }

    private static Map<Integer, Double> getThreeTierFeeRates(FeeRatesSource feeRatesSource, Map<Integer, Double> defaultblockTargetFeeRates, String url) {
        if(log.isInfoEnabled()) {
            log.info("Requesting fee rates from " + url);
        }

        Map<Integer, Double> blockTargetFeeRates = new LinkedHashMap<>();
        HttpClientService httpClientService = AppServices.getHttpClientService();
        try {
            ThreeTierRates threeTierRates = feeRatesSource.getThreeTierRates(url, httpClientService);
            Double lastRate = null;
            for(Integer blockTarget : defaultblockTargetFeeRates.keySet()) {
                if(blockTarget < BLOCKS_IN_HALF_HOUR) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.fastestFee);
                } else if(blockTarget < BLOCKS_IN_HOUR) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.halfHourFee);
                } else if(blockTarget < BLOCKS_IN_TWO_HOURS || defaultblockTargetFeeRates.get(blockTarget) > threeTierRates.hourFee) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.hourFee);
                } else if(threeTierRates.minimumFee != null && defaultblockTargetFeeRates.get(blockTarget) < threeTierRates.minimumFee) {
                    blockTargetFeeRates.put(blockTarget, threeTierRates.minimumFee + (threeTierRates.hourFee > threeTierRates.minimumFee ? threeTierRates.hourFee * 0.2 : 0.0));
                } else {
                    blockTargetFeeRates.put(blockTarget, defaultblockTargetFeeRates.get(blockTarget));
                }

                if(lastRate != null) {
                    blockTargetFeeRates.put(blockTarget, Math.min(lastRate, blockTargetFeeRates.get(blockTarget)));
                }
                lastRate = blockTargetFeeRates.get(blockTarget);
            }

            if(threeTierRates.minimumFee != null) {
                blockTargetFeeRates.put(Integer.MAX_VALUE, threeTierRates.minimumFee);
            }
        } catch (Exception e) {
            if(log.isDebugEnabled()) {
                log.warn("Error retrieving recommended fee rates from " + url, e);
            } else {
                log.warn("Error retrieving recommended fee rates from " + url + " (" + e.getMessage() + ")");
            }
        }

        return blockTargetFeeRates;
    }

    protected ThreeTierRates getThreeTierRates(String url, HttpClientService httpClientService) throws Exception {
        return httpClientService.requestJson(url, ThreeTierRates.class, null);
    }

    @Override
    public String toString() {
        return name;
    }

    private record ThreeTierRates(Double fastestFee, Double halfHourFee, Double hourFee, Double minimumFee) {}

    private record OxtRates(OxtRatesData[] data) {}

    private record OxtRatesData(Double recommended_fee_099, Double recommended_fee_090, Double recommended_fee_050) {
        public ThreeTierRates getThreeTierRates() {
            return new ThreeTierRates(recommended_fee_099/1000, recommended_fee_090/1000, recommended_fee_050/1000, null);
        }
    }
}
