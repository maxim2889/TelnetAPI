package com.manaldush.telnet.protocol;

import com.manaldush.telnet.exceptions.GeneralTelnetException;

import java.io.IOException;

/**
 * Created by Maxim.Melnikov on 26.06.2017.
 */
public interface ITelnetCommandProcessor {
    void process() throws IOException, GeneralTelnetException;
}
