package org.codice.imaging.nitf.nei;

/** Indicates that an NEI extension payload does not match its fixed-width layout. */
public class NeiFormatException extends RuntimeException {
    public NeiFormatException(final String message) {
        super(message);
    }

    public NeiFormatException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

