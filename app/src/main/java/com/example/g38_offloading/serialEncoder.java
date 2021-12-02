package com.example.g38_offloading;

import java.io.Serializable;

public class serialEncoder implements Serializable {

    private int[] rowResult;
    private int row;
    private String deviceName;

    //constructor
    public serialEncoder(int[] rowResult, int row, String deviceName) {
        this.setrowResult(rowResult);
        this.setRow(row);
        this.setDeviceName(deviceName);
    }

    //getter methods
    public int[] getrowResult() {
        return rowResult;
    }

    public void setrowResult(int[] rowResult) {
        this.rowResult = rowResult;
    }



    public int getRow() {
        return row;
    }

    //setter methods

    public void setRow(int row) {
        this.row = row;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }
}