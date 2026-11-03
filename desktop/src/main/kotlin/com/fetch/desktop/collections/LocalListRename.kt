package com.fetch.desktop.collections

import com.fetch.tv.domain.model.Collection
import com.fetch.tv.domain.model.LocalListCollectionSource

/**
 * [collections] with every source that shows local list [listId] retitled [name], as the TV does
 * when you rename a list; null when no source uses it.
 */
fun renameLocalListSources(collections: List<Collection>, listId: String, name: String): List<Collection>? {
    var changed = false
    val out = collections.map { c ->
        c.copy(folders = c.folders.map { f ->
            f.copy(sources = f.sources.map { s ->
                if (s is LocalListCollectionSource && s.listId == listId && s.title != name) {
                    changed = true
                    s.copy(title = name)
                } else s
            })
        })
    }
    return out.takeIf { changed }
}
