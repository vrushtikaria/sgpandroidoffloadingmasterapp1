package com.example.g38_offloading;

import java.io.Serializable;

public class serialDecoder implements Serializable {

    private int[] a;
    private int[][] b;
    private int row;

    //initialization
    public serialDecoder(int[] a, int[][] b, int row) {
        this.setA(a);
        this.setB(b);
        this.setRow(row);
    }

    //getter and setters
    public int[] getA() {
        return a;
    }

    public void setA(int[] a) {
        this.a = a;
    }

    public int[][] getB() {
        return b;
    }

    public void setB(int[][] b) {
        this.b = b;
    }

    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }
}
