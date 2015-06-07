function SyncDataStore() {
	SyncDataStore.prototype.initialize = function(apiPath, types, callback, error){
		cordova.exec(callback, error, "SyncDataStore", "initialize", [apiPath, JSON.stringify(types)]);
    };
    SyncDataStore.prototype.register = function(cookie, callback, error){
		cordova.exec(callback, error, "SyncDataStore", "register", [cookie]);
    };
	SyncDataStore.prototype.unRegister = function(callback, error){
		cordova.exec(callback, error, "SyncDataStore", "unRegister", []);
    };
	SyncDataStore.prototype.update = function(callback, error){
		cordova.exec(callback, error, "SyncDataStore", "update", []);
	};
	SyncDataStore.prototype.requestSync = function(callback, error){
		cordova.exec(callback, error, "SyncDataStore", "requestSync", []);
	};
	SyncDataStore.prototype.getLastUpdateTimestamp = function(collectionName, callback, error){
		cordova.exec(callback, error, "SyncDataStore", "getLastUpdateTimestamp", [collectionName]);
	};
	SyncDataStore.prototype.isSyncing = function(callback, error){
		cordova.exec(callback, error, "SyncDataStore", "isSyncing", []);
	};
	SyncDataStore.prototype.listen = function(typeName, callback, error){
		cordova.exec(callback, error, "SyncDataStore", "listen", [typeName]);
	};
};
module.exports = new SyncDataStore();

