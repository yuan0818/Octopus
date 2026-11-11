package io.yuan.octopus.review.adapter;

/** Public diagnostics contain only classified failure metadata, never provider response bodies. */
public class ModelCallException extends IllegalStateException {
    public final String code;
    public final Integer upstreamStatus;
    public ModelCallException(String code,Integer status) { super(code);this.code=code;this.upstreamStatus=status; }
    public static ModelCallException http(int status) {
        return new ModelCallException(switch(status) {
            case 401,403 -> "MODEL_AUTH";case 429 -> "MODEL_RATE_LIMIT";case 408,504 -> "MODEL_TIMEOUT";
            case 400,404,422 -> "MODEL_CONFIG";default -> "MODEL_UNAVAILABLE";
        },status);
    }
    public static ModelCallException classify(Throwable e) {
        for(Throwable c=e;c!=null;c=c.getCause()) {
            if(c instanceof ModelCallException m)return m;
            if(c instanceof java.net.http.HttpTimeoutException || c instanceof java.net.SocketTimeoutException)return new ModelCallException("MODEL_TIMEOUT",null);
            if(c instanceof com.google.gson.JsonParseException)return new ModelCallException("MODEL_RESPONSE_INVALID",null);
        }
        return new ModelCallException("MODEL_UNAVAILABLE",null);
    }
}
