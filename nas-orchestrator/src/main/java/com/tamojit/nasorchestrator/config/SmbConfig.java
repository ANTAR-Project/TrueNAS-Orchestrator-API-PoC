package com.tamojit.nasorchestrator.config;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class SmbConfig {
    @Bean
    public CIFSContext cifsContext(
        @Value("${smb.username}") String smbUsername,
        @Value("${smb.password}") String smbPassword
    ) throws CIFSException {
        Properties props = new Properties();

        props.setProperty("jcifs.smb.client.minVersion", "SMB202");
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311");

        CIFSContext baseContext = new BaseContext(new PropertyConfiguration(props));
        NtlmPasswordAuthenticator auth = new NtlmPasswordAuthenticator("", smbUsername, smbPassword);

        return baseContext.withCredentials(auth);
    }
}
