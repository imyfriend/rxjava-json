package com.trunk.rx.json.token;

public class JsonComma extends BaseToken {
  private static final class Holder {
    private static final JsonComma INSTANCE = new JsonComma();
  }

  public static JsonComma instance() {
    return Holder.INSTANCE;
  }

  @Override
  public String value() {
    return ",";
  }
}
