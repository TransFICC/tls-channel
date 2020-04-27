package tlschannel;

import java.io.IOException;

/**
 * This exception signals the caller that the operation cannot continue because a tls handshake
 * needs to be performed. Should not be thrown unless explicit handshake has been enabled.
 */
public class NeedsHandshakeException extends IOException {}
