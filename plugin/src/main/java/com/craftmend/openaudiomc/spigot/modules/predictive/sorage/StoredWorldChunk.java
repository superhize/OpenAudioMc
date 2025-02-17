package com.craftmend.openaudiomc.spigot.modules.predictive.sorage;

import com.craftmend.openaudiomc.generic.database.internal.DataStore;
import com.craftmend.openaudiomc.spigot.modules.predictive.serialization.SerializedAudioChunk;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StoredWorldChunk extends DataStore {

    private String chunkName;
    private SerializedAudioChunk.Chunk audioChunk;

}
