package org.eu.pcraft.pepperminecart.template;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.eu.pcraft.pepperminecart.PepperMinecart;

@Getter
public class ConfigTemplate {
    public void loadConfig() {
        FileConfiguration conf = PepperMinecart.getInstance().getConfig();
        enableCustomInteract = conf.getBoolean("enableCustomInteract");
    }

    private boolean enableCustomInteract;
}