package com.example.examplemod;

/**
 * Fundamental interaction primitive for worker entities.
 * Workers only have two primary interaction types: LEFT_CLICK and RIGHT_CLICK.
 */
public enum ActionType {
    LEFT_CLICK,
    RIGHT_CLICK;

    public boolean isLeftClick() {
        return this == LEFT_CLICK;
    }

    public boolean isRightClick() {
        return this == RIGHT_CLICK;
    }
}
