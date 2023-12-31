package com.tiscon.service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiscon.code.OptionalServiceType;
import com.tiscon.code.PackageType;
import com.tiscon.dao.EstimateDao;
import com.tiscon.domain.Customer;
import com.tiscon.domain.CustomerOptionService;
import com.tiscon.domain.CustomerPackage;
import com.tiscon.domain.Prefecture;
import com.tiscon.dto.UserOrderDto;



/**
 * 引越し見積もり機能において業務処理を担当するクラス。
 *
 * @author Oikawa Yumi
 */
@Service
public class EstimateService {

    /** 引越しする距離の1 kmあたりの料金[円] */
    private static final int PRICE_PER_DISTANCE = 100;

    private static final String GEOCODER_URL_TEMPLATE = "https://map.yahooapis.jp/geocode/V1/geoCoder?appid=dj00aiZpPXRFcmJkSVhUbkt5SyZzPWNvbnN1bWVyc2VjcmV0Jng9NDM-&query=%s&output=json";
    private static final String DISTANCE_API_URL_TEMPLATE = "https://map.yahooapis.jp/dist/V1/distance?appid=dj00aiZpPXRFcmJkSVhUbkt5SyZzPWNvbnN1bWVyc2VjcmV0Jng9NDM-&coordinates=%s %s&output=json";

    private final EstimateDao estimateDAO;

    private final RestTemplate restTemplate;


    /**
     * コンストラクタ。
     *
     * @param estimateDAO EstimateDaoクラス
     */
    public EstimateService(EstimateDao estimateDAO) {
        this.estimateDAO = estimateDAO;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 見積もり依頼をDBに登録する。
     *
     * @param dto 見積もり依頼情報
     */
    @Transactional
    public void registerOrder(UserOrderDto dto) {
        Customer customer = new Customer();
        BeanUtils.copyProperties(dto, customer);
        estimateDAO.insertCustomer(customer);

        if (dto.getWashingMachineInstallation()) {
            CustomerOptionService washingMachine = new CustomerOptionService();
            washingMachine.setCustomerId(customer.getCustomerId());
            washingMachine.setServiceId(OptionalServiceType.WASHING_MACHINE.getCode());
            estimateDAO.insertCustomersOptionService(washingMachine);
        }

        List<CustomerPackage> packageList = new ArrayList<>();

        packageList.add(new CustomerPackage(customer.getCustomerId(), PackageType.BOX.getCode(), dto.getBox()));
        packageList.add(new CustomerPackage(customer.getCustomerId(), PackageType.BED.getCode(), dto.getBed()));
        packageList.add(new CustomerPackage(customer.getCustomerId(), PackageType.BICYCLE.getCode(), dto.getBicycle()));
        packageList.add(new CustomerPackage(customer.getCustomerId(), PackageType.WASHING_MACHINE.getCode(), dto.getWashingMachine()));
        estimateDAO.batchInsertCustomerPackage(packageList);
    }

    /**
     * 見積もり依頼に応じた概算見積もりを行う。
     *
     * @param dto 見積もり依頼情報
     * @return 概算見積もり結果の料金
     */
    public Integer getPrice(UserOrderDto dto) {
        int distanceInt;

        try {
            distanceInt = getDistanceByApi(dto);
        } catch (RuntimeException e) {
            e.printStackTrace();

            double distance = estimateDAO.getDistance(dto.getOldPrefectureId(), dto.getNewPrefectureId());
            // 小数点以下を切り捨てる
            distanceInt = (int) Math.floor(distance);
        }

        // 距離当たりの料金を算出する
        int priceForDistance = distanceInt * PRICE_PER_DISTANCE;

        int boxes = getBoxForPackage(dto.getBox(), PackageType.BOX)
                + getBoxForPackage(dto.getBed(), PackageType.BED)
                + getBoxForPackage(dto.getBicycle(), PackageType.BICYCLE)
                + getBoxForPackage(dto.getWashingMachine(), PackageType.WASHING_MACHINE);

        // 箱に応じてトラックの種類が変わり、それに応じて料金が変わるためトラック料金を算出する。
        int pricePerTruck = estimateDAO.getPricePerTruck(boxes);

        // オプションサービスの料金を算出する。
        int priceForOptionalService = 0;

        if (dto.getWashingMachineInstallation()) {
            priceForOptionalService = estimateDAO.getPricePerOptionalService(OptionalServiceType.WASHING_MACHINE.getCode());
        }

        float seasonWait = (dto.getMoveMonth().equals("3") || dto.getMoveMonth().equals("4")) ? 1.5F : dto.getMoveMonth().equals("9") ? 1.2F : 1;

        float priceFloat = (priceForDistance + pricePerTruck) * seasonWait + priceForOptionalService;

        return Math.round(priceFloat);
    }

    private int getDistanceByApi(UserOrderDto dto) {
        List<Prefecture> allPrefectures = estimateDAO.getAllPrefectures();
        Prefecture oldPrefecture = allPrefectures.stream().filter(it -> it.getPrefectureId().equals(dto.getOldPrefectureId())).collect(Collectors.toList()).get(0);
        Prefecture newPrefecture = allPrefectures.stream().filter(it -> it.getPrefectureId().equals(dto.getNewPrefectureId())).collect(Collectors.toList()).get(0);

        String oldAddressCordinates = getCoordinates(oldPrefecture.getPrefectureName() + dto.getOldAddress());
        String newAddressCordinates = getCoordinates(newPrefecture.getPrefectureName() + dto.getNewAddress());

        JsonNode body = restTemplate.getForObject(String.format(DISTANCE_API_URL_TEMPLATE, oldAddressCordinates, newAddressCordinates), JsonNode.class);
        return body.get("Feature").get(0).get("Geometry").get("Distance").asInt();
    }

    private String getCoordinates(String address) {
        JsonNode body = restTemplate.getForObject(String.format(GEOCODER_URL_TEMPLATE, address), JsonNode.class);
        return body.get("Feature").get(0).get("Geometry").get("Coordinates").asText();
    }

    /**
     * 荷物当たりの段ボール数を算出する。
     *
     * @param packageNum 荷物数
     * @param type       荷物の種類
     * @return 段ボール数
     */
    private int getBoxForPackage(int packageNum, PackageType type) {
        return packageNum * estimateDAO.getBoxPerPackage(type.getCode());
    }
}