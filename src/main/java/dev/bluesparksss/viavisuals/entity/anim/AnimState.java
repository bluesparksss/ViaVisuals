package dev.bluesparksss.viavisuals.entity.anim;

public final class AnimState {

  private boolean running;
  private int origin;

  public boolean running() {
    return running;
  }

  public double elapsedMs(double age) {
    return 50.0 * (age - origin);
  }

  public void begin(int tick) {
    origin = tick;
    running = true;
  }

  public void beginIfIdle(int tick) {
    if (!running) {
      begin(tick);
    }
  }

  public void halt() {
    running = false;
  }

  public void runWhile(boolean cond, int tick) {
    if (!cond) {
      halt();
      return;
    }
    beginIfIdle(tick);
  }

  public void skip(int ticks, float rate) {
    if (running) {
      origin -= (int) (ticks * rate);
    }
  }
}
