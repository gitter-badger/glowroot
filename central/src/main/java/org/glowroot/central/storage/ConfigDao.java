/*
 * Copyright 2015-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.central.storage;

import java.io.IOException;

import javax.annotation.Nullable;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigDao {

    private static final Logger logger = LoggerFactory.getLogger(ConfigDao.class);

    private final Session session;

    private final PreparedStatement insertPS;

    public ConfigDao(Session session) {
        this.session = session;
        session.execute("create table if not exists config (server_id varchar, key varchar,"
                + " value varchar, primary key (server_id, key))");
        insertPS = session.prepare("insert into config (server_id, key, value) values (?, ?, ?)");
    }

    void write(String serverId, String key, Object config, ObjectMapper mapper)
            throws JsonProcessingException {
        BoundStatement boundStatement = insertPS.bind();
        boundStatement.setString(0, serverId);
        boundStatement.setString(1, key);
        boundStatement.setString(2, mapper.writeValueAsString(config));
        session.execute(boundStatement);
    }

    @Nullable
    <T> T read(String serverId, String key, Class<T> clazz, ObjectMapper mapper) {
        ResultSet results = session.execute("select value from config where server_id = ?"
                + " and key = ?", serverId, key);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        String value = row.getString(0);
        if (value == null) {
            return null;
        }
        try {
            return mapper.readValue(value, clazz);
        } catch (IOException e) {
            logger.error("error parsing config node '{}': ", key, e);
            return null;
        }
    }

    <T extends /*@NonNull*/ Object> /*@Nullable*/ T read(String serverId, String key,
            TypeReference<T> typeReference, ObjectMapper mapper) {
        ResultSet results = session.execute("select value from config where server_id = ?"
                + " and key = ?", serverId, key);
        Row row = results.one();
        if (row == null) {
            return null;
        }
        String value = row.getString(0);
        if (value == null) {
            return null;
        }
        try {
            return mapper.readValue(value, typeReference);
        } catch (IOException e) {
            logger.error("error parsing config node '{}': ", key, e);
            return null;
        }
    }
}
