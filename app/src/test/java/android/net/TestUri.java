package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public class TestUri extends Uri {

    public TestUri() {
        super();
    }

    @Override
    public boolean isHierarchical() {
        return false;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String toString() {
        return "bitchord://stream/test";
    }

    @Override
    public String getScheme() {
        return "bitchord";
    }

    @Override
    public String getSchemeSpecificPart() {
        return "//stream/test";
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        return "//stream/test";
    }

    @Override
    public String getAuthority() {
        return "stream";
    }

    @Override
    public String getEncodedAuthority() {
        return "stream";
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return "stream";
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        return "/test";
    }

    @Override
    public String getEncodedPath() {
        return "/test";
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public List<String> getPathSegments() {
        return Collections.singletonList("test");
    }

    @Override
    public String getLastPathSegment() {
        return "test";
    }

    @Override
    public Builder buildUpon() {
        return null;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
    }
}
