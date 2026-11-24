package util;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

public class ExchangeRateResponse {

    @SerializedName("result")
    public String result;

    @SerializedName("base_code")
    public String baseCode;

    @SerializedName("time_last_update_utc")
    public String lastUpdateUtc;

    @SerializedName("time_next_update_utc")
    public String nextUpdateUtc;

    @SerializedName("rates")
    public Map<String, Double> rates;
}
