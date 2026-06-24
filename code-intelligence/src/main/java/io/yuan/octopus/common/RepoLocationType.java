package io.yuan.octopus.common;

public enum RepoLocationType {

    DIRECT(0),
    CONFIGURED(1);

    private final int code;

    RepoLocationType(int code){
        this.code = code;
    }

    public int getCode(){
        return this.code;
    }

}
