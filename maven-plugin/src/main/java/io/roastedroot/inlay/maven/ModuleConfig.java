package io.roastedroot.inlay.maven;

import java.io.File;

public class ModuleConfig {

    private String imageRef;
    private String packageRef;
    private File outputFile;
    private String sigstoreIssuer;
    private String sigstoreIdentity;

    public String getImageRef() {
        return imageRef;
    }

    public void setImageRef(String imageRef) {
        this.imageRef = imageRef;
    }

    public String getPackageRef() {
        return packageRef;
    }

    public void setPackageRef(String packageRef) {
        this.packageRef = packageRef;
    }

    public File getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    public String getSigstoreIssuer() {
        return sigstoreIssuer;
    }

    public void setSigstoreIssuer(String sigstoreIssuer) {
        this.sigstoreIssuer = sigstoreIssuer;
    }

    public String getSigstoreIdentity() {
        return sigstoreIdentity;
    }

    public void setSigstoreIdentity(String sigstoreIdentity) {
        this.sigstoreIdentity = sigstoreIdentity;
    }
}
