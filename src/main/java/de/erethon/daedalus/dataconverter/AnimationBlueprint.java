package de.erethon.daedalus.dataconverter;

import de.erethon.daedalus.utils.InterpolationType;
import de.erethon.daedalus.utils.LoopType;
import de.erethon.daedalus.utils.MathToolkit;
import de.erethon.bedrock.chat.MessageUtil;

import java.util.*;

public class AnimationBlueprint {

    private final HashMap<BoneBlueprint, List<Keyframe>> boneKeyframes = new HashMap<>();
    private final HashMap<BoneBlueprint, AnimationFrame[]> animationFrames = new HashMap<>();
    private LoopType loopType;
    private String animationName;
    private SkeletonBlueprint skeletonBlueprint;
    private int duration;

    public AnimationBlueprint(Object data, String modelName, SkeletonBlueprint skeletonBlueprint) {
        Map<String, Object> animationData;
        try {
            animationData = (Map<String, Object>) data;
        } catch (Exception e) {
            MessageUtil.log("Failed to get animation data! Model format is not as expected, this version of BlockBench is not compatible with Daedalus!");
            e.printStackTrace();
            return;
        }

        this.skeletonBlueprint = skeletonBlueprint;
        initializeGlobalValues(animationData);

        if (animationData.get("animators") == null) return;
        //In BBModel files, each bone holds the data for their transformations, so data is stored from the bone's perspective
        ((Map<String, Object>) animationData.get("animators")).entrySet().forEach(pair -> initializeBones((Map<String, Object>) pair.getValue(), modelName, animationName));

        //Process the keyframes
        try {
            interpolateKeyframes();
        } catch (Exception e) {
            MessageUtil.log("Failed to interpolate animations for model " + modelName + "! Animation name: " + animationName);
            e.printStackTrace();
        }
    }

    private void initializeGlobalValues(Map<String, Object> animationData) {
        //Parse global data for animation
        animationName = (String) animationData.get("name");
        loopType = LoopType.valueOf(((String) animationData.get("loop")).toUpperCase());
        duration = (int) (20 * (Double) animationData.get("length"));
    }

    private void initializeBones(Map<String, Object> animationData, String modelName, String animationName) {
        String boneName = (String) animationData.get("name");
        BoneBlueprint boneBlueprint = skeletonBlueprint.getBoneMap().get(boneName);
        //hitboxes do not get animated!
        if (boneName.equalsIgnoreCase("hitbox")) return;
        if (boneBlueprint == null) {
            MessageUtil.log("Failed to get bone " + boneName + " from model " + modelName + "!");
            return;
        }
        List<Keyframe> keyframes = new ArrayList<>();
        for (Object keyframeData : (List) animationData.get("keyframes")) {
            keyframes.add(new Keyframe(keyframeData, modelName, animationName));
        }
        keyframes.sort(Comparator.comparingInt(Keyframe::getTimeInTicks));
        boneKeyframes.put(boneBlueprint, keyframes);
    }

    private void interpolateKeyframes() {
        boneKeyframes.forEach(this::interpolateBoneKeyframes);
    }

    private void interpolateBoneKeyframes(BoneBlueprint boneBlueprint, List<Keyframe> keyframes) {
        List<Keyframe> rotationKeyframes = new ArrayList<>();
        List<Keyframe> positionKeyframes = new ArrayList<>();
        List<Keyframe> scaleKeyframes = new ArrayList<>();
        for (Keyframe keyframe : keyframes) {
            switch (keyframe.getTransformationType()) {
                case ROTATION -> rotationKeyframes.add(keyframe);
                case POSITION -> positionKeyframes.add(keyframe);
                case SCALE -> scaleKeyframes.add(keyframe);
            }
        }

        AnimationFrame[] animationFramesArray = new AnimationFrame[duration];
        for (int i = 0; i < animationFramesArray.length; i++)
            animationFramesArray[i] = new AnimationFrame();

        //Interpolation time
        interpolateRotations(animationFramesArray, rotationKeyframes);
        interpolateTranslations(animationFramesArray, positionKeyframes);
        interpolateScales(animationFramesArray, scaleKeyframes);

        this.animationFrames.put(boneBlueprint, animationFramesArray);
    }

    /**
     * Helper method to call the appropriate interpolation based on type
     */
    private float interpolateWithType(InterpolationType type, float start, float end, float t) {
        switch (type) {
            case LINEAR -> {
                return MathToolkit.lerp(start, end, t);
            }
            case CATMULLROM -> {
                return MathToolkit.smoothLerp(start, end, t);
            }
            case BEZIER -> {
                // You can adjust these control points or make them configurable
                return MathToolkit.bezierLerp(start, end, t, 0.42f, 0.58f); // ease-in-out preset
            }
            case STEP -> {
                return MathToolkit.stepLerp(start, end, t);
            }
            default -> {
                return MathToolkit.lerp(start, end, t); // fallback to linear
            }
        }
    }

    private void interpolateRotations(AnimationFrame[] animationFramesArray, List<Keyframe> rotationKeyframes) {
        Keyframe firstFrame = null;
        Keyframe previousFrame = null;
        Keyframe lastFrame = null;
        for (int i = 0; i < rotationKeyframes.size(); i++) {
            Keyframe animationFrame = rotationKeyframes.get(i);
            if (i == 0) {
                firstFrame = animationFrame;
                previousFrame = animationFrame;
                lastFrame = animationFrame;
                continue;
            }
            //It is possible for frames to go beyond the animation's duration, so we need to clamp that
            if (previousFrame.getTimeInTicks() >= duration) return;
            int durationBetweenKeyframes = Math.min(animationFrame.getTimeInTicks(), duration) - previousFrame.getTimeInTicks();

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                float t = j / (float) durationBetweenKeyframes;

                animationFramesArray[currentFrame].xRotation = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t);
                animationFramesArray[currentFrame].yRotation = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t);
                animationFramesArray[currentFrame].zRotation = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t);
            }
            previousFrame = animationFrame;
            if (animationFrame.getTimeInTicks() > lastFrame.getTimeInTicks()) lastFrame = animationFrame;
            if (animationFrame.getTimeInTicks() < firstFrame.getTimeInTicks()) firstFrame = animationFrame;
        }
        if (lastFrame != null && lastFrame.getTimeInTicks() < duration - 1) {
            int durationBetweenKeyframes = duration - lastFrame.getTimeInTicks();
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                animationFramesArray[currentFrame].xRotation = lastFrame.getDataX();
                animationFramesArray[currentFrame].yRotation = lastFrame.getDataY();
                animationFramesArray[currentFrame].zRotation = lastFrame.getDataZ();
            }
        }
        if (firstFrame != null && firstFrame.getTimeInTicks() > 0) {
            int durationBetweenKeyframes = firstFrame.getTimeInTicks();
            durationBetweenKeyframes = Math.min(durationBetweenKeyframes, duration - 1);
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                animationFramesArray[j].xRotation = firstFrame.getDataX();
                animationFramesArray[j].yRotation = firstFrame.getDataY();
                animationFramesArray[j].zRotation = firstFrame.getDataZ();
            }
        }
    }

    private void interpolateTranslations(AnimationFrame[] animationFramesArray, List<Keyframe> positionKeyframes) {
        Keyframe firstFrame = null;
        Keyframe previousFrame = null;
        Keyframe lastFrame = null;
        for (int i = 0; i < positionKeyframes.size(); i++) {
            Keyframe animationFrame = positionKeyframes.get(i);
            if (i == 0) {
                firstFrame = animationFrame;
                previousFrame = animationFrame;
                lastFrame = animationFrame;
                continue;
            }
            int durationBetweenKeyframes = animationFrame.getTimeInTicks() - previousFrame.getTimeInTicks();

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                float t = j / (float) durationBetweenKeyframes;

                animationFramesArray[currentFrame].xPosition = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t) / 16f;
                animationFramesArray[currentFrame].yPosition = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t) / 16f;
                animationFramesArray[currentFrame].zPosition = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t) / 16f;
            }
            previousFrame = animationFrame;
            if (animationFrame.getTimeInTicks() > lastFrame.getTimeInTicks()) lastFrame = animationFrame;
            if (animationFrame.getTimeInTicks() < firstFrame.getTimeInTicks()) firstFrame = animationFrame;
        }
        if (lastFrame != null && lastFrame.getTimeInTicks() < duration - 1) {
            int durationBetweenKeyframes = duration - lastFrame.getTimeInTicks();
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                animationFramesArray[currentFrame].xPosition = lastFrame.getDataX() / 16f;
                animationFramesArray[currentFrame].yPosition = lastFrame.getDataY() / 16f;
                animationFramesArray[currentFrame].zPosition = lastFrame.getDataZ() / 16f;
            }
        }
        if (firstFrame != null && firstFrame.getTimeInTicks() > 0) {
            int durationBetweenKeyframes = firstFrame.getTimeInTicks();
            durationBetweenKeyframes = Math.min(durationBetweenKeyframes, duration - 1);
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                animationFramesArray[j].xPosition = firstFrame.getDataX() / 16f;
                animationFramesArray[j].yPosition = firstFrame.getDataY() / 16f;
                animationFramesArray[j].zPosition = firstFrame.getDataZ() / 16f;
            }
        }
    }

    private void interpolateScales(AnimationFrame[] animationFramesArray, List<Keyframe> scaleKeyframes) {
        Keyframe previousFrame = null;
        for (int i = 0; i < scaleKeyframes.size(); i++) {
            Keyframe animationFrame = scaleKeyframes.get(i);
            if (i == 0) {
                previousFrame = animationFrame;
                continue;
            }
            int durationBetweenKeyframes = animationFrame.getTimeInTicks() - previousFrame.getTimeInTicks();

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                float t = j / (float) durationBetweenKeyframes;

                animationFramesArray[currentFrame].scale = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t);
            }
            previousFrame = animationFrame;
        }
    }

    // Getters
    public SkeletonBlueprint getSkeletonBlueprint() {
        return skeletonBlueprint;
    }

    public String getAnimationName() {
        return animationName;
    }
    public LoopType getLoopType() {
        return loopType;
    }

    public int getDuration() {
        return duration;
    }

    public HashMap<BoneBlueprint, AnimationFrame[]> getAnimationFrames() {
        return animationFrames;
    }

    public HashMap<BoneBlueprint, List<Keyframe>> getBoneKeyframes() {
        return boneKeyframes;
    }

}
