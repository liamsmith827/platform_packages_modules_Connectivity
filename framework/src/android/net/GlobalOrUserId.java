package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.UserHandle;

import java.util.Objects;

// TODO: Move NonNull to above method?

// TODO: Does it matter that USER_CURRENT is -2?

/**
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public class GlobalOrUserId {

    private static final int FIRST_USER_ID = 0;
    private static final int GLOBAL_PLACEHOLDER_USER_ID = FIRST_USER_ID - 1;

    public static final @NonNull GlobalOrUserId GLOBAL = new GlobalOrUserId(true,
            GLOBAL_PLACEHOLDER_USER_ID);

    private final boolean mGlobal;
    private final int mUserId;

    // TODO: Rename this to user.
    public static @NonNull GlobalOrUserId userId(int userId) {
        if (userId < FIRST_USER_ID) {
            throw new IllegalArgumentException();
        }
        return new GlobalOrUserId(false, userId);
    }

    // TODO: Rename this to currentUser.
    public static @NonNull GlobalOrUserId currentUserId() {
        return userId(UserHandle.myUserId());
    }

    private GlobalOrUserId(boolean global, int userId) {
        mGlobal = global;
        mUserId = userId;
    }

    public boolean isGlobal() {
        return mGlobal;
    }

    public int getUserId() {
        if (mUserId == GLOBAL_PLACEHOLDER_USER_ID) {
            // TODO: UnsupportedOperation?
            throw new UnsupportedOperationException("global target does not have a userId");
        }
        return mUserId;
    }

    public @NonNull UserHandle getUserHandle() {
        if (mUserId == GLOBAL_PLACEHOLDER_USER_ID) {
            // TODO: UnsupportedOperation?
            throw new UnsupportedOperationException("global target does not have a user handle");
        }
        return UserHandle.of(mUserId);
    }

    @Override
    public String toString() {
        if (mGlobal) {
            return "global";
        }
        return String.valueOf(mUserId);
    }

    // TODO: This and hashcode might not be needed now that no map structure is used.
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GlobalOrUserId other)) {
            return false;
        }

        return mGlobal == other.mGlobal &&
                mUserId == other.mUserId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mGlobal, mUserId);
    }
}