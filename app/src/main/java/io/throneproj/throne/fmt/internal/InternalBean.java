package io.throneproj.throne.fmt.internal;

import io.throneproj.throne.fmt.AbstractBean;

public abstract class InternalBean extends AbstractBean {

    @Override
    public String displayAddress() {
        return "";
    }

    @Override
    public boolean canMapping() {
        return false;
    }
}
