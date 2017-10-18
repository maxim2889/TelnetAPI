package com.manaldush.telnet.options;

import com.manaldush.telnet.IClientSession;

import java.nio.charset.Charset;
import java.util.List;

public class DefaultOption extends Option {
    public DefaultOption(byte _v, boolean _isClientSupported, boolean _isServerSupported) {
        super(_v, _isClientSupported, _isServerSupported);
    }

    @Override
    protected void innerSubNegotiation(List<Byte> _b, IClientSession _session, Charset _charset) {

    }
}
