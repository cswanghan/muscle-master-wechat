package com.jisuodashi.inventory;

/** uk_occ collision — last oversell line. Caller reverts that bed and tries the next. */
public class DuplicateOccupancyException extends RuntimeException {

    public DuplicateOccupancyException(String message) {
        super(message);
    }

    public DuplicateOccupancyException(String message, Throwable cause) {
        super(message, cause);
    }
}
