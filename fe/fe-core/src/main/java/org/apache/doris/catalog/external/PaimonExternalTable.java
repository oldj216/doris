// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog.external;

import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.Type;
import org.apache.doris.datasource.paimon.PaimonExternalCatalog;
import org.apache.doris.statistics.AnalysisInfo;
import org.apache.doris.statistics.BaseAnalysisTask;
import org.apache.doris.statistics.ExternalAnalysisTask;
import org.apache.doris.thrift.THiveTable;
import org.apache.doris.thrift.TTableDescriptor;
import org.apache.doris.thrift.TTableType;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.paimon.schema.TableSchema;
import org.apache.paimon.table.AbstractFileStoreTable;
import org.apache.paimon.table.Table;
import org.apache.paimon.types.ArrayType;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DecimalType;
import org.apache.paimon.types.MapType;

import java.util.HashMap;
import java.util.List;

public class PaimonExternalTable extends ExternalTable {

    private static final Logger LOG = LogManager.getLogger(PaimonExternalTable.class);

    private Table originTable = null;

    public PaimonExternalTable(long id, String name, String dbName, PaimonExternalCatalog catalog) {
        super(id, name, catalog, dbName, TableType.PAIMON_EXTERNAL_TABLE);
    }

    public String getPaimonCatalogType() {
        return ((PaimonExternalCatalog) catalog).getCatalogType();
    }

    protected synchronized void makeSureInitialized() {
        super.makeSureInitialized();
        if (!objectCreated) {
            originTable = ((PaimonExternalCatalog) catalog).getPaimonTable(dbName, name);
            schemaUpdateTime = System.currentTimeMillis();
            objectCreated = true;
        }
    }

    public Table getOriginTable() {
        makeSureInitialized();
        return originTable;
    }

    @Override
    public List<Column> initSchema() {
        //init schema need update lastUpdateTime and get latest schema
        objectCreated = false;
        Table table = getOriginTable();
        TableSchema schema = ((AbstractFileStoreTable) table).schema();
        List<DataField> columns = schema.fields();
        List<Column> tmpSchema = Lists.newArrayListWithCapacity(columns.size());
        for (DataField field : columns) {
            tmpSchema.add(new Column(field.name(),
                    paimonTypeToDorisType(field.type()), true, null, true, field.description(), true,
                    field.id()));
        }
        return tmpSchema;
    }

    private Type paimonPrimitiveTypeToDorisType(org.apache.paimon.types.DataType dataType) {
        switch (dataType.getTypeRoot()) {
            case BOOLEAN:
                return Type.BOOLEAN;
            case INTEGER:
                return Type.INT;
            case BIGINT:
                return Type.BIGINT;
            case FLOAT:
                return Type.FLOAT;
            case DOUBLE:
                return Type.DOUBLE;
            case SMALLINT:
                return Type.SMALLINT;
            case TINYINT:
                return Type.TINYINT;
            case VARCHAR:
            case BINARY:
            case CHAR:
            case VARBINARY:
                return Type.STRING;
            case DECIMAL:
                DecimalType decimal = (DecimalType) dataType;
                return ScalarType.createDecimalV3Type(decimal.getPrecision(), decimal.getScale());
            case DATE:
                return ScalarType.createDateV2Type();
            case TIMESTAMP_WITHOUT_TIME_ZONE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                int scale = 3; // default
                if (dataType instanceof org.apache.paimon.types.TimestampType) {
                    scale = ((org.apache.paimon.types.TimestampType) dataType).getPrecision();
                    if (scale > 6) {
                        scale = 6;
                    }
                }
                return ScalarType.createDatetimeV2Type(scale);
            case ARRAY:
                ArrayType arrayType = (ArrayType) dataType;
                Type innerType = paimonPrimitiveTypeToDorisType(arrayType.getElementType());
                return org.apache.doris.catalog.ArrayType.create(innerType, true);
            case MAP:
                MapType mapType = (MapType) dataType;
                return new org.apache.doris.catalog.MapType(
                        paimonTypeToDorisType(mapType.getKeyType()), paimonTypeToDorisType(mapType.getValueType()));
            case TIME_WITHOUT_TIME_ZONE:
                return Type.UNSUPPORTED;
            default:
                LOG.warn("Cannot transform unknown type: " + dataType.getTypeRoot());
                return Type.UNSUPPORTED;
        }
    }

    protected Type paimonTypeToDorisType(org.apache.paimon.types.DataType type) {
        return paimonPrimitiveTypeToDorisType(type);
    }

    @Override
    public TTableDescriptor toThrift() {
        List<Column> schema = getFullSchema();
        if (PaimonExternalCatalog.PAIMON_HMS.equals(getPaimonCatalogType()) || PaimonExternalCatalog.PAIMON_FILESYSTEM
                .equals(getPaimonCatalogType())) {
            THiveTable tHiveTable = new THiveTable(dbName, name, new HashMap<>());
            TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.HIVE_TABLE, schema.size(), 0,
                    getName(), dbName);
            tTableDescriptor.setHiveTable(tHiveTable);
            return tTableDescriptor;
        } else {
            throw new IllegalArgumentException("Currently only supports hms/filesystem catalog,not support :"
                    + getPaimonCatalogType());
        }
    }

    @Override
    public BaseAnalysisTask createAnalysisTask(AnalysisInfo info) {
        makeSureInitialized();
        return new ExternalAnalysisTask(info);
    }
}
