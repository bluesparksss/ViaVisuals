package dev.bluesparksss.viavisuals.entity.anim;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;

public final class AnimDrivers {

  private AnimDrivers() {
  }

  public static AnimDriver create(String id) {
    return switch (id) {
      case "breeze" -> new Breeze();
      case "warden" -> new Warden();
      case "frog" -> new Frog();
      case "camel", "camel_husk" -> new Camel();
      case "sniffer" -> new Sniffer();
      case "armadillo" -> new Armadillo();
      case "creaking" -> new Creaking();
      case "bat" -> new Bat();
      case "rabbit" -> new Rabbit();
      case "nautilus", "zombie_nautilus" -> new Nautilus();
      case "chicken" -> new Chicken();
      case "copper_golem" -> new CopperGolem();
      case "bogged", "parched", "skeleton", "stray" -> new Skeleton();
      case "wolf" -> new Wolf();
      case "cat", "ocelot" -> new Cat();
      case "fox" -> new Fox();
      case "polar_bear" -> new PolarBear();
      case "dolphin" -> new Dolphin();
      case "turtle" -> new Turtle();
      case "bee" -> new Bee();
      case "sheep" -> new Sheep();
      case "goat" -> new Goat();
      case "horse", "donkey", "mule", "skeleton_horse", "zombie_horse" -> new Horse();
      case "villager", "zombie_villager" -> new Villager();
      case "strider" -> new Strider();
      case "axolotl" -> new Axolotl();
      case "allay" -> new Allay();
      default -> new Living();
    };
  }

  static class Living extends AnimDriver {

    private boolean swinging;
    private int swingTime;
    private float swimAmount;

    @Override
    protected void tick(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      if (has(events, count, EVENT_SWING)) {
        swingTime = -1;
        swinging = true;
      }
      if (swinging) {
        swingTime++;
        if (swingTime >= 6) {
          swingTime = 0;
          swinging = false;
        }
      }
      if (ent == null) {
        return;
      }
      mob.flags.put("swingAnimation", swinging ? swingTime / 6.0 : 0.0);
      mob.flags.put("isInWater", ent.isInWater());
      mob.flags.put("isPassenger", ent.isInsideVehicle());
      boolean crouching = pose.equals("SNEAKING") || pose.equals("CROUCHING");
      mob.flags.put("isCrouching", crouching);
      boolean swimming = pose.equals("SWIMMING");
      swimAmount = Math.max(0f, Math.min(1f, swimAmount + (swimming ? 0.09f : -0.09f)));
      mob.flags.put("swimAmount", (double) swimAmount);
      if (ent instanceof org.bukkit.entity.Mob m) {
        mob.flags.put("isAggressive", m.isAggressive());
      }
      living(ent, pose, poseChanged, events, count);
    }

    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
    }
  }

  static final class Breeze extends Living {
    private final AnimState idle = new AnimState();
    private final AnimState shoot = new AnimState();
    private final AnimState slide = new AnimState();
    private final AnimState slideBack = new AnimState();
    private final AnimState inhale = new AnimState();
    private final AnimState longJump = new AnimState();

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      int tick = age();
      if (poseChanged) {
        shoot.halt();
        idle.halt();
        inhale.halt();
        longJump.halt();
        switch (pose) {
          case "SHOOTING" -> shoot.beginIfIdle(tick);
          case "INHALING" -> inhale.beginIfIdle(tick);
          case "SLIDING" -> slide.beginIfIdle(tick);
          default -> {
          }
        }
      }
      if (pose.equals("LONG_JUMPING")) {
        longJump.beginIfIdle(tick);
      }
      idle.beginIfIdle(tick);
      if (!pose.equals("SLIDING") && slide.running()) {
        slideBack.begin(tick);
        slide.halt();
      }
    }

    @Override
    public void apply(double[] pose) {
      play("idleAnimation", idle, pose);
      play("shootAnimation", shoot, pose);
      play("slideAnimation", slide, pose);
      play("slideBackAnimation", slideBack, pose);
      play("inhaleAnimation", inhale, pose);
      play("jumpAnimation", longJump, pose);
    }
  }

  static final class Warden extends Living {
    private final AnimState roar = new AnimState();
    private final AnimState sniff = new AnimState();
    private final AnimState emerge = new AnimState();
    private final AnimState digging = new AnimState();
    private final AnimState attack = new AnimState();
    private final AnimState sonicBoom = new AnimState();
    private int tendril;
    private int tendrilO;
    private int heart;
    private int heartO;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      int tick = age();
      if (poseChanged) {
        switch (pose) {
          case "EMERGING" -> emerge.begin(tick);
          case "DIGGING" -> digging.begin(tick);
          case "ROARING" -> roar.begin(tick);
          case "SNIFFING" -> sniff.begin(tick);
          default -> {
          }
        }
      }
      if (has(events, count, 4)) {
        roar.halt();
        attack.begin(tick);
      }
      if (has(events, count, 61)) {
        tendril = 10;
      }
      if (has(events, count, 62)) {
        sonicBoom.begin(tick);
      }
      String level = Refl.enumName(ent, "getAngerLevel");
      int delay = switch (level) {
        case "ANGRY" -> 10;
        case "AGITATED" -> 25;
        default -> 40;
      };
      if (tick % delay == 0) {
        heart = 10;
      }
      tendrilO = tendril;
      if (tendril > 0) {
        tendril--;
      }
      heartO = heart;
      if (heart > 0) {
        heart--;
      }
      mob.flags.put("tendrilAnimation", (tendrilO + (tendril - tendrilO) * 1.0) / 10.0);
      mob.flags.put("heartAnimation", (heartO + (heart - heartO) * 1.0) / 10.0);
    }

    @Override
    public void apply(double[] pose) {
      play("attackAnimation", attack, pose);
      play("sonicBoomAnimation", sonicBoom, pose);
      play("diggingAnimation", digging, pose);
      play("emergeAnimation", emerge, pose);
      play("roarAnimation", roar, pose);
      play("sniffAnimation", sniff, pose);
    }
  }

  static final class Frog extends Living {
    private final AnimState jump = new AnimState();
    private final AnimState croak = new AnimState();
    private final AnimState tongue = new AnimState();
    private final AnimState swimIdle = new AnimState();

    Frog() {
    }

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      int tick = age();
      mob.walkMultiplier = jump.running() ? 0f : 25f;
      swimIdle.runWhile(ent != null && ent.isInWater() && mob.walkSpeed() < 1.0E-5, tick);
      if (poseChanged) {
        if (pose.equals("LONG_JUMPING")) {
          jump.begin(tick);
        } else {
          jump.halt();
        }
        if (pose.equals("CROAKING")) {
          croak.begin(tick);
        } else {
          croak.halt();
        }
        if (pose.equals("USING_TONGUE")) {
          tongue.begin(tick);
        } else {
          tongue.halt();
        }
      }
      mob.flags.put("isSwimming", ent != null && ent.isInWater());
    }

    @Override
    public void apply(double[] pose) {
      play("jumpAnimation", jump, pose);
      play("croakAnimation", croak, pose);
      play("tongueAnimation", tongue, pose);
      if (Boolean.TRUE.equals(mob.flags.get("isSwimming"))) {
        walk("swimAnimation", 1.0, 2.5, pose);
      } else {
        walk("walkAnimation", 1.5, 2.5, pose);
      }
      play("idleWaterAnimation", swimIdle, pose);
    }

    @Override
    public void visibility(PoseSolver solver) {
      setVisible(solver, "/body/croaking_body", croak.running());
    }
  }

  static final class Camel extends Living {
    private final AnimState sit = new AnimState();
    private final AnimState sitPose = new AnimState();
    private final AnimState sitUp = new AnimState();
    private final AnimState idle = new AnimState();
    private final AnimState dash = new AnimState();
    private int idleTimeout;
    private boolean sitting;
    private int poseTime = 1000;
    private final java.util.Random random = new java.util.Random();

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      int tick = age();
      boolean nowSitting = Refl.bool(ent, "isSitting") || pose.equals("SITTING");
      if (nowSitting != sitting) {
        sitting = nowSitting;
        poseTime = 0;
      } else if (poseTime < 1000) {
        poseTime++;
      }
      boolean transition = poseTime < (sitting ? 40 : 52);
      boolean dashing = Refl.bool(ent, "isDashing");
      if (idleTimeout <= 0) {
        idleTimeout = random.nextInt(40) + 80;
        idle.begin(tick);
      } else {
        idleTimeout--;
      }
      if (sitting) {
        sitUp.halt();
        dash.halt();
        if (transition) {
          sit.beginIfIdle(tick);
          sitPose.halt();
        } else {
          sit.halt();
          sitPose.beginIfIdle(tick);
        }
      } else {
        sit.halt();
        sitPose.halt();
        dash.runWhile(dashing, tick);
        sitUp.runWhile(transition && poseTime < 52, tick);
      }
      mob.walkMultiplier = !sitting && !dash.running() ? 6f : 0f;
      mob.walkSmoothing = 0.2f;
    }

    @Override
    public void apply(double[] pose) {
      walk("walkAnimation", 2.0, 2.5, pose);
      play("sitAnimation", sit, pose);
      play("sitPoseAnimation", sitPose, pose);
      play("standupAnimation", sitUp, pose);
      play("idleAnimation", idle, pose);
      play("dashAnimation", dash, pose);
    }
  }

  static final class Sniffer extends Living {
    private final AnimState happy = new AnimState();
    private final AnimState scenting = new AnimState();
    private final AnimState sniffing = new AnimState();
    private final AnimState digging = new AnimState();
    private final AnimState rising = new AnimState();
    private String state = "";

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      String now = Refl.enumName(ent, "getState");
      int tick = age();
      if (!now.equals(state)) {
        state = now;
        digging.halt();
        sniffing.halt();
        rising.halt();
        happy.halt();
        scenting.halt();
        switch (now) {
          case "FEELING_HAPPY" -> happy.beginIfIdle(tick);
          case "SCENTING" -> scenting.beginIfIdle(tick);
          case "SNIFFING" -> sniffing.beginIfIdle(tick);
          case "DIGGING" -> digging.beginIfIdle(tick);
          case "RISING" -> rising.beginIfIdle(tick);
          default -> {
          }
        }
      }
      mob.flags.put("isSearching", now.equals("SEARCHING"));
    }

    @Override
    public void apply(double[] pose) {
      if (state.equals("SEARCHING")) {
        walk("sniffSearchAnimation", 9.0, 100.0, pose);
      } else {
        walk("walkAnimation", 9.0, 100.0, pose);
      }
      play("diggingAnimation", digging, pose);
      play("longSniffAnimation", sniffing, pose);
      play("standUpAnimation", rising, pose);
      play("happyAnimation", happy, pose);
      play("sniffSniffAnimation", scenting, pose);
    }
  }

  static final class Armadillo extends Living {
    private final AnimState rollOut = new AnimState();
    private final AnimState rollUp = new AnimState();
    private final AnimState peek = new AnimState();
    private String state = "IDLE";
    private int inState;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      String now = Refl.enumName(ent, "getState");
      if (now.isEmpty()) {
        now = "IDLE";
      }
      int tick = age();
      if (!now.equals(state)) {
        state = now;
        inState = 0;
      } else {
        inState++;
      }
      switch (state) {
        case "ROLLING" -> {
          rollOut.halt();
          rollUp.beginIfIdle(tick);
          peek.halt();
        }
        case "SCARED" -> {
          rollOut.halt();
          rollUp.halt();
          if (inState == 0) {
            peek.begin(tick);
            peek.skip(50, 1.0f);
          } else {
            peek.beginIfIdle(tick);
          }
        }
        case "UNROLLING" -> {
          rollOut.beginIfIdle(tick);
          rollUp.halt();
          peek.halt();
        }
        default -> {
          rollOut.halt();
          rollUp.halt();
          peek.halt();
        }
      }
      boolean hide = switch (state) {
        case "ROLLING" -> inState > 5;
        case "SCARED" -> true;
        case "UNROLLING" -> inState < 26;
        default -> false;
      };
      mob.flags.put("isHidingInShell", hide);
    }

    @Override
    public void apply(double[] pose) {
      if (!Boolean.TRUE.equals(mob.flags.get("isHidingInShell"))) {
        walk("walkAnimation", 16.5, 2.5, pose);
      }
      play("rollOutAnimation", rollOut, pose);
      play("rollUpAnimation", rollUp, pose);
      play("peekAnimation", peek, pose);
    }
  }

  static final class Creaking extends Living {
    private final AnimState attack = new AnimState();
    private final AnimState invulnerable = new AnimState();
    private final AnimState death = new AnimState();
    private int attackTicks;
    private int invulnerableTicks;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      if (has(events, count, 66)) {
        invulnerableTicks = 8;
      }
      if (has(events, count, 4)) {
        attackTicks = 15;
      }
      if (invulnerableTicks > 0) {
        invulnerableTicks--;
      }
      if (attackTicks > 0) {
        attackTicks--;
      }
      int tick = age();
      attack.runWhile(attackTicks > 0, tick);
      invulnerable.runWhile(invulnerableTicks > 0, tick);
      death.runWhile(Refl.bool(ent, "isTearingDown"), tick);
      mob.flags.put("canMove", true);
      mob.walkMultiplier = 25f;
      mob.walkMax = 3f;
      mob.walkBabyScale = 1f;
    }

    @Override
    public void apply(double[] pose) {
      walk("walkAnimation", 1.0, 1.0, pose);
      play("attackAnimation", attack, pose);
      play("invulnerableAnimation", invulnerable, pose);
      play("deathAnimation", death, pose);
    }
  }

  static final class Bat extends Living {
    private final AnimState fly = new AnimState();
    private final AnimState rest = new AnimState();

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean resting = ent instanceof org.bukkit.entity.Bat bat && !bat.isAwake();
      mob.flags.put("isResting", resting);
      int tick = age();
      if (resting) {
        fly.halt();
        rest.beginIfIdle(tick);
      } else {
        rest.halt();
        fly.beginIfIdle(tick);
      }
    }

    @Override
    public void apply(double[] pose) {
      play("flyingAnimation", fly, pose);
      play("restingAnimation", rest, pose);
    }
  }

  static final class Rabbit extends Living {
    private final AnimState hop = new AnimState();
    private final AnimState idleTilt = new AnimState();
    private int jumpTicks;
    private int jumpDuration;
    private int idleTimeout;
    private final java.util.Random random = new java.util.Random();

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      if (has(events, count, 1)) {
        jumpDuration = 15;
        jumpTicks = 0;
      }
      if (jumpTicks != jumpDuration) {
        jumpTicks++;
      } else if (jumpDuration != 0) {
        jumpTicks = 0;
        jumpDuration = 0;
      }
      int tick = age();
      boolean noAi = ent instanceof org.bukkit.entity.Mob m && !m.hasAI();
      if (idleTimeout <= 0 && !noAi && !ent.isInsideVehicle()) {
        idleTimeout = random.nextInt(40) + 180;
        idleTilt.begin(tick);
      } else if (jumpTicks > 0) {
        hop.beginIfIdle(tick);
        idleTilt.halt();
      } else {
        idleTimeout--;
        hop.halt();
      }
      mob.flags.put("idleHeadTilt", idleTilt.running());
    }

    @Override
    public void apply(double[] pose) {
      play("hopAnimation", hop, pose);
      play("idleHeadTiltAnimation", idleTilt, pose);
    }
  }

  static final class Nautilus extends Living {
    @Override
    public void apply(double[] pose) {
      double time = (mob.walkPosition() + mob.age() / 5.0) * 50.0 * 2.0;
      double scale = Math.min((mob.walkSpeed() + 0.2) * 3.0, 1.0);
      at("swimAnimation", time, scale, pose);
    }
  }

  static final class Chicken extends Living {
    private float flap;
    private float flapSpeed;
    private float flapping;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean ground = ent.isOnGround() || ent.isInsideVehicle();
      flapSpeed += (ground ? -1.0f : 4.0f) * 0.3f;
      flapSpeed = Math.max(0f, Math.min(1f, flapSpeed));
      if (!ent.isOnGround() && flapping < 1.0f) {
        flapping = 1.0f;
      }
      flapping *= 0.9f;
      flap += flapping * 2.0f;
    }

    @Override
    public void apply(double[] pose) {
      float angle = ((float) Math.sin(flap) + 1.0f) * flapSpeed;
      int right = mob.rig.indexOfPath("/right_wing");
      int left = mob.rig.indexOfPath("/left_wing");
      if (right >= 0) {
        pose[right * RigData.CH + 5] += angle;
      }
      if (left >= 0) {
        pose[left * RigData.CH + 5] -= angle;
      }
    }
  }

  static final class Wolf extends Living {
    private float interested;
    private float interestedO;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean angry = Refl.bool(ent, "isAngry");
      mob.flags.put("isAngry", angry);
      mob.flags.put("isSitting", Refl.bool(ent, "isSitting"));
      double tail;
      if (angry) {
        tail = 1.5393804;
      } else if (Refl.bool(ent, "isTamed") && ent instanceof LivingEntity living) {
        tail = (0.55 - (living.getMaxHealth() - living.getHealth()) * 0.02) * Math.PI;
      } else {
        tail = 0.62831855;
      }
      mob.flags.put("tailAngle", tail);
      interestedO = interested;
      interested += ((Refl.bool(ent, "isInterested") ? 1f : 0f) - interested) * 0.4f;
      mob.flags.put("headRollAngle", (double) ((interestedO + (interested - interestedO)) * 0.15f * (float) Math.PI));
    }
  }

  static final class Cat extends Living {
    private float lie;
    private float lieTail;
    private float relax;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      mob.flags.put("isSitting", Refl.bool(ent, "isSitting"));
      mob.flags.put("isSprinting", Refl.bool(ent, "isSprinting"));
      if (Refl.bool(ent, "isLyingDown")) {
        lie = Math.min(1f, lie + 0.15f);
        lieTail = Math.min(1f, lieTail + 0.08f);
      } else {
        lie = Math.max(0f, lie - 0.22f);
        lieTail = Math.max(0f, lieTail - 0.13f);
      }
      if (Refl.bool(ent, "isHeadUp")) {
        relax = Math.min(1f, relax + 0.1f);
      } else {
        relax = Math.max(0f, relax - 0.13f);
      }
      mob.flags.put("lieDownAmount", (double) lie);
      mob.flags.put("lieDownAmountTail", (double) lieTail);
      mob.flags.put("relaxStateOneAmount", (double) relax);
    }
  }

  static final class Fox extends Living {
    private float interested;
    private float crouch;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean crouching = Refl.bool(ent, "isCrouching");
      mob.flags.put("isSitting", Refl.bool(ent, "isSitting"));
      mob.flags.put("isSleeping", Refl.bool(ent, "isSleeping"));
      mob.flags.put("isFaceplanted", Refl.bool(ent, "isFaceplanted"));
      mob.flags.put("isPouncing", Refl.bool(ent, "isLeaping") || Refl.bool(ent, "isPouncing"));
      mob.flags.put("isCrouching", crouching);
      interested += ((Refl.bool(ent, "isInterested") ? 1f : 0f) - interested) * 0.4f;
      mob.flags.put("headRollAngle", (double) (interested * 0.11f * (float) Math.PI));
      if (crouching) {
        crouch = Math.min(5f, crouch + 0.2f);
      } else {
        crouch = 0f;
      }
      mob.flags.put("crouchAmount", (double) crouch);
    }

    @Override
    public void apply(double[] pose) {
      walk("babyWalkAnimation", 1.0, 2.5, pose);
    }
  }

  static final class PolarBear extends Living {
    private float stand;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      stand = Math.max(0f, Math.min(6f, stand + (Refl.bool(ent, "isStanding") ? 1f : -1f)));
      mob.flags.put("standScale", (double) (stand / 6f));
    }
  }

  static final class Dolphin extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      org.bukkit.util.Vector v = ent.getVelocity();
      mob.flags.put("isMoving", v.getX() * v.getX() + v.getZ() * v.getZ() > 1.0E-7);
    }
  }

  static final class Turtle extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      mob.flags.put("isOnLand", !ent.isInWater() && ent.isOnGround());
    }
  }

  static final class Bee extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      org.bukkit.util.Vector v = ent.getVelocity();
      mob.flags.put("isOnGround", ent.isOnGround() && v.lengthSquared() < 1.0E-7);
      mob.flags.put("hasStinger", !Refl.bool(ent, "hasStung"));
      mob.flags.put("isAngry", Refl.num(ent, "getAnger", 0) > 0);
    }
  }

  static final class Sheep extends Living {
    private int eat;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      eat = Math.max(0, eat - 1);
      if (has(events, count, 10)) {
        eat = 40;
      }
      double position;
      if (eat <= 0) {
        position = 0;
      } else if (eat >= 4 && eat <= 36) {
        position = 1;
      } else {
        position = eat < 4 ? eat / 4.0 : -(eat - 40) / 4.0;
      }
      double angle;
      if (eat > 4 && eat <= 36) {
        double scale = (eat - 4) / 32.0;
        angle = Math.PI / 5 + 0.21991149 * Math.sin(scale * 28.7);
      } else {
        angle = eat > 0 ? Math.PI / 5 : Math.toRadians(ent.getLocation().getPitch());
      }
      mob.flags.put("headEatPositionScale", position);
      mob.flags.put("headEatAngleScale", angle);
    }
  }

  static final class Goat extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      Object left = Refl.call(ent, "hasLeftHorn");
      Object right = Refl.call(ent, "hasRightHorn");
      mob.flags.put("hasLeftHorn", !(left instanceof Boolean l) || l);
      mob.flags.put("hasRightHorn", !(right instanceof Boolean r) || r);
    }
  }

  static final class Horse extends Living {
    private float eat;
    private float stand;
    private float mouth;
    private int tailCounter;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean eating = Refl.bool(ent, "isEatingHaystack") || Refl.bool(ent, "isEatingGrass");
      boolean standing = Refl.bool(ent, "isRearing");
      if (eating) {
        eat = Math.min(1f, eat + ((1f - eat) * 0.4f + 0.05f));
      } else {
        eat = Math.max(0f, eat + ((0f - eat) * 0.4f - 0.05f));
      }
      if (standing) {
        eat = 0f;
        stand = Math.min(1f, stand + ((1f - stand) * 0.4f + 0.05f));
      } else {
        stand = Math.max(0f, stand + ((0f - stand) * 0.4f - 0.05f));
      }
      mob.flags.put("eatAnimation", (double) eat);
      mob.flags.put("standAnimation", (double) stand);
      mob.flags.put("feedingAnimation", (double) mouth);
      mob.flags.put("isRidden", !ent.getPassengers().isEmpty());
      if (has(events, count, 7)) {
        tailCounter = 1;
      }
      if (tailCounter > 0 && ++tailCounter > 8) {
        tailCounter = 0;
      }
      mob.flags.put("animateTail", tailCounter > 0);
    }
  }

  static final class Villager extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      mob.flags.put("isUnhappy", Refl.num(ent, "getUnhappyCounter", 0) > 0 || Refl.bool(ent, "isUnhappy"));
    }
  }

  static final class Strider extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      mob.flags.put("isRidden", !ent.getPassengers().isEmpty());
    }
  }

  static final class Axolotl extends Living {
    private float inWater;
    private float onGround;
    private float moving;
    private float playingDead;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      inWater = approach(inWater, ent.isInWater() ? 1f : 0f);
      onGround = approach(onGround, ent.isOnGround() ? 1f : 0f);
      org.bukkit.util.Vector v = ent.getVelocity();
      moving = approach(moving, v.getX() * v.getX() + v.getZ() * v.getZ() > 1.0E-6 ? 1f : 0f);
      playingDead = approach(playingDead, Refl.bool(ent, "isPlayingDead") ? 1f : 0f);
      mob.flags.put("inWaterFactor", (double) inWater);
      mob.flags.put("onGroundFactor", (double) onGround);
      mob.flags.put("movingFactor", (double) moving);
      mob.flags.put("playingDeadFactor", (double) playingDead);
    }

    private static float approach(float cur, float target) {
      return cur + Math.max(-0.1f, Math.min(0.1f, target - cur));
    }
  }

  static final class Allay extends Living {
    private float holding;
    private float spinning;

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean hasItem = ent instanceof LivingEntity living && living.getEquipment() != null
          && !living.getEquipment().getItemInMainHand().getType().isAir();
      holding = Math.max(0f, Math.min(5f, holding + (hasItem ? 1f : -1f)));
      mob.flags.put("holdingAnimationProgress", (double) (holding / 5f));
      mob.flags.put("isDancing", Refl.bool(ent, "isDancing"));
      mob.flags.put("isSpinning", false);
      mob.flags.put("spinningProgress", (double) spinning);
    }
  }

  static final class Skeleton extends Living {
    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      boolean bow = ent instanceof LivingEntity living && living.getEquipment() != null
          && living.getEquipment().getItemInMainHand().getType().name().equals("BOW");
      boolean aggressive = ent instanceof org.bukkit.entity.Mob m && m.isAggressive();
      mob.flags.put("isHoldingBow", bow);
      mob.flags.put("rightArmPose", bow && aggressive ? "BOW_AND_ARROW" : "EMPTY");
      mob.flags.put("leftArmPose", "EMPTY");
      mob.flags.put("isSheared", Refl.bool(ent, "isSheared"));
    }
  }

  static final class CopperGolem extends Living {
    private final AnimState idle = new AnimState();
    private final AnimState getItem = new AnimState();
    private final AnimState getNoItem = new AnimState();
    private final AnimState dropItem = new AnimState();
    private final AnimState dropNoItem = new AnimState();
    private int idleStart;
    private boolean holding;
    private final java.util.Random random = new java.util.Random();

    @Override
    protected void living(Entity ent, String pose, boolean poseChanged, int[] events, int count) {
      int tick = age();
      if (ent instanceof LivingEntity living && living.getEquipment() != null) {
        holding = !living.getEquipment().getItemInMainHand().getType().isAir()
            || !living.getEquipment().getItemInOffHand().getType().isAir();
      }
      String state = Refl.enumName(ent, "getState");
      switch (state) {
        case "GETTING_ITEM" -> {
          idle.halt();
          idleStart = 0;
          getNoItem.halt();
          dropItem.halt();
          dropNoItem.halt();
          getItem.beginIfIdle(tick);
        }
        case "GETTING_NO_ITEM" -> {
          idle.halt();
          idleStart = 0;
          getItem.halt();
          dropNoItem.halt();
          dropItem.halt();
          getNoItem.beginIfIdle(tick);
        }
        case "DROPPING_ITEM" -> {
          idle.halt();
          idleStart = 0;
          getItem.halt();
          getNoItem.halt();
          dropNoItem.halt();
          dropItem.beginIfIdle(tick);
        }
        case "DROPPING_NO_ITEM" -> {
          idle.halt();
          idleStart = 0;
          getItem.halt();
          getNoItem.halt();
          dropItem.halt();
          dropNoItem.beginIfIdle(tick);
        }
        default -> {
          getNoItem.halt();
          getItem.halt();
          dropItem.halt();
          dropNoItem.halt();
          if (idleStart == tick) {
            idle.begin(tick);
          } else if (idleStart == 0) {
            idleStart = tick + 200 + random.nextInt(40);
          }
          if (tick == idleStart + 10) {
            idleStart = 0;
          }
        }
      }
    }

    @Override
    public void apply(double[] pose) {
      if (holding) {
        walk("walkWithItemAnimation", 2.0, 2.5, pose);
        int right = mob.rig.indexOfPath("/body/right_arm");
        int left = mob.rig.indexOfPath("/body/left_arm");
        if (right >= 0) {
          int r = right * RigData.CH;
          pose[r + 3] = Math.min(pose[r + 3], -0.87266463);
          pose[r + 4] = Math.min(pose[r + 4], -0.1134464);
          pose[r + 5] = Math.min(pose[r + 5], -0.064577185);
        }
        if (left >= 0) {
          int l = left * RigData.CH;
          pose[l + 3] = Math.min(pose[l + 3], -0.87266463);
          pose[l + 4] = Math.max(pose[l + 4], 0.1134464);
          pose[l + 5] = Math.max(pose[l + 5], 0.064577185);
        }
      } else {
        walk("walkAnimation", 2.0, 2.5, pose);
      }
      play("idleAnimation", idle, pose);
      play("interactionGetItem", getItem, pose);
      play("interactionGetNoItem", getNoItem, pose);
      play("interactionDropItem", dropItem, pose);
      play("interactionDropNoItem", dropNoItem, pose);
    }
  }

  static String lower(Object value) {
    return String.valueOf(value).toLowerCase(Locale.ROOT);
  }

  static boolean isLiving(Entity ent) {
    return ent instanceof LivingEntity;
  }
}
