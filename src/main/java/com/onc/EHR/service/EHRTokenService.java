package com.onc.EHR.service;

/**
 * Obtains an access token for the upstream EHR provider API.
 *
 * <p>Single definition shared by every certification module. Previously each module carried
 * its own copy differing only in the configuration prefix it read.
 */
public interface EHRTokenService {

    String getAccessToken();
}
