package com.unciv.app.desktop;

import com.badlogic.gdx.backends.lwjgl.LwjglApplication;
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.tools.texturepacker.TexturePacker;
import com.unciv.UnCivGame;

class DesktopLauncher {
	public static void main (String[] arg) {

		TexturePacker.Settings settings = new TexturePacker.Settings();
		settings.maxWidth = 2500;
		settings.maxHeight = 2500;
		settings.combineSubdirectories=true;
		settings.pot=false;
		settings.fast=true;

		// This is so they don't look all pixelated
		settings.filterMag = Texture.TextureFilter.MipMapLinearLinear;
		settings.filterMin =  Texture.TextureFilter.MipMapLinearLinear;
		TexturePacker.process(settings, "../Images", ".", "game");

		LwjglApplicationConfiguration config = new LwjglApplicationConfiguration();
		new LwjglApplication(new UnCivGame("Desktop"), config);
	}
}
