package com.example.g38_offloading;

import java.io.Serializable;

public class serialEncoder implements Serializable {

    private static final long serialVersionUID = 6529685098267757691L;
    private int[] a;
    private int[][] b;
    private int row;

    //initialization
    public serialEncoder(int[] a, int[][] b, int row) {
        this.setA(a);
        this.setB(b);
        this.setRow(row);
    }

    //getter and setters

    public void setA(int[] a) {
        this.a = a;
    }

    public void setB(int[][] b) {
        this.b = b;
    }

    public void setRow(int row) {
        this.row = row;
    }
}
