package com.example.modulewithhelpers.withdirectory;

import com.google.common.math.IntMath;

public class Helper {
  public static int powerOfOne(int x) {
    return IntMath.checkedPow(x, 1);
  }
}
