package org.mozilla.focus.screenshot.model;

import java.io.Serializable;

/**
 * Created by hart on 15/08/2017.
 */

public class Screenshot implements Serializable {
    private long id;
    private String title;
    private String url;
    private long timestamp;
    private String imageUri;

    public Screenshot(){
    }

    public Screenshot(String title, String url, long timestamp, String imageUri) {
        this.title = title;
        this.url = url;
        this.timestamp = timestamp;
        this.imageUri = imageUri;
    }

    public long getId() {
        return this.id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return this.title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrl() {
        return this.url;
    }

    public long getTimestamp() {
        return this.timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getImageUri() {
        return this.imageUri;
    }

    public void setImageUri(String imageUri) {
        this.imageUri = imageUri;
    }

    @Override
    public String toString() {
        return "Screenshot{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", url='" + url + '\'' +
                ", timestamp=" + timestamp +
                ", imageUri='" + imageUri + '\'' +
                '}';
    }
}
