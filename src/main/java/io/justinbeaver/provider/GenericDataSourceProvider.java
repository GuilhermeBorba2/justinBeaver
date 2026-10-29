package io.justinbeaver.provider;

import io.justinbeaver.driver.DriverDescriptor;
import org.springframework.stereotype.Component;

/** Any JDBC database that needs nothing beyond a URL template and user/password. */
@Component
public class GenericDataSourceProvider implements DataSourceProvider {

    @Override
    public String id() {
        return DriverDescriptor.GENERIC_PROVIDER;
    }
}
