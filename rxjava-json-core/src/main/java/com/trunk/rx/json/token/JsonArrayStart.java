package com.trunk.rx.json.token;

public class JsonArrayStart extends BaseToken {

  private static final class Holder {
    private static final JsonArrayStart INSTANCE = new JsonArrayStart();
  }

  public static JsonArrayStart instance() {
    return Holder.INSTANCE;
  }

  private JsonArrayStart() {
    // do nothing
  }

  @Override
  public boolean isArrayStart() {
    return true;
  }

  @Override
  public String value() {
    return "[";
  }

  @Override
  public String toString() {
    return "JsonArrayStart{}";
  }
}
