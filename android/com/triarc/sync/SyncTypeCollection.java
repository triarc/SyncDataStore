package com.triarc.sync;

import java.util.ArrayList;

public class SyncTypeCollection {
	private String action;
	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	private String controller;
	public String getController() {
		return controller;
	}

	public void setController(String controller) {
		this.controller = controller;
	}

	private String name;
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	private ArrayList<SyncType> types = new ArrayList<SyncType>();

	public ArrayList<SyncType> getTypes() {
		return types;
	}

	public void setTypes(ArrayList<SyncType> types) {
		this.types = types;
	}
}
