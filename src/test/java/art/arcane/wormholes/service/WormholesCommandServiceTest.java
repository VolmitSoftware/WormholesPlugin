package art.arcane.wormholes.service;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeNode;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.wormholes.commands.CommandWormholes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WormholesCommandServiceTest {
    @Test
    void normalizeHelpArgsKeepsLegacyHelpSpellings() {
        assertArrayEquals(new String[]{"help=1"}, WormholesCommandService.normalizeHelpArgs(new String[]{"help"}));
        assertArrayEquals(new String[]{"help=2"}, WormholesCommandService.normalizeHelpArgs(new String[]{"?", "2"}));
        assertArrayEquals(new String[]{"wand", "help=3"}, WormholesCommandService.normalizeHelpArgs(new String[]{"wand", "help", "3"}));
        assertArrayEquals(new String[]{"wand", "rune=portal"}, WormholesCommandService.normalizeHelpArgs(new String[]{"wand", "rune=portal"}));
    }

    @Test
    void directorTreeIncludesRootAliasAndFlatCommands() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorRuntimeNode root = engine.getRoot();

        assertEquals(List.of("wh", "wormhole"), List.copyOf(root.getDescriptor().getAliases()));
        assertNotNull(findChild(root, "wand"));
        assertNotNull(findChild(root, "door"));
        assertNotNull(findChild(root, "reload"));
        assertNotNull(findChild(root, "info"));
        assertNotNull(findChild(root, "debug"));
        DirectorRuntimeNode network = findChild(root, "network");
        assertNotNull(network);
        assertNotNull(findChild(network, "status"));
        assertNotNull(findChild(network, "doctor"));
        assertNotNull(findChild(network, "import"));
        assertNotNull(findChild(root, "admin"));
        assertNull(findChild(root, "rune"));
        assertNull(findChild(root, "reset"));
    }

    @Test
    void dimensionalDoorTypeCompletionOffersCanonicalValues() {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandWormholes(null));
        DirectorSender sender = directorSender();

        assertEquals(
            List.of("type=pair", "type=personal", "type=public"),
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=")))
        );
        assertEquals(
            List.of("type=pair", "type=personal", "type=public"),
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=p")))
        );
        assertEquals(
            List.of("type=public"),
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=pub")))
        );
        assertEquals(
            List.of("type="),
            engine.tabComplete(new DirectorInvocation(sender, "wormholes", List.of("door", "type=unknown")))
        );
        assertThrows(
            DirectorParsingException.class,
            () -> new CommandWormholes.DoorTypeHandler().parse(" ", false)
        );
    }

	@Test
	void publicTabCompletionOnlyOffersHelpAndInfo()
	{
		assertEquals(List.of("help", "info"), WormholesCommandService.publicTabCompletions(new String[] {""}));
		assertEquals(List.of("info"), WormholesCommandService.publicTabCompletions(new String[] {"i"}));
		assertEquals(List.of(), WormholesCommandService.publicTabCompletions(new String[] {"network", ""}));
	}

	@Test
	void publicExecutionOnlyAllowsHelpAndInfo()
	{
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[0]));
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[] {"help"}));
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[] {"?"}));
		assertEquals(true, WormholesCommandService.isPublicCommandRequest(new String[] {"info"}));
		assertEquals(false, WormholesCommandService.isPublicCommandRequest(new String[] {"reload"}));
		assertEquals(false, WormholesCommandService.isPublicCommandRequest(new String[] {"network", "status"}));
		assertEquals(false, WormholesCommandService.isPublicCommandRequest(new String[] {"info", "extra"}));
	}

	@Test
	void nonAdministratorExecutionCannotReachMutatingDirectorCommands()
	{
		List<String> messages = new ArrayList<>();
		CommandSender sender = commandSender(messages);
		WormholesCommandService service = new WormholesCommandService(null);
		Command command = new Command("wormholes")
		{
			@Override
			public boolean execute(CommandSender sender, String label, String[] args)
			{
				return false;
			}
		};

		assertTrue(service.onCommand(sender, command, "wormholes", new String[] {"reload"}));
		assertTrue(messages.stream().anyMatch(message -> message.contains("do not have permission")));
	}

	private static CommandSender commandSender(List<String> messages)
	{
		return (CommandSender) Proxy.newProxyInstance(
			WormholesCommandServiceTest.class.getClassLoader(),
			new Class<?>[] {CommandSender.class},
			(proxy, method, args) ->
			{
				if(method.getName().equals("getName"))
				{
					return "guest";
				}
				if(method.getName().equals("hasPermission") || method.getName().equals("isPermissionSet") || method.getName().equals("isOp"))
				{
					return false;
				}
				if(method.getName().equals("sendMessage") && args != null)
				{
					for(Object value : args)
					{
						if(value instanceof String message)
						{
							messages.add(message);
						}
						else if(value instanceof Component component)
						{
							messages.add(PlainTextComponentSerializer.plainText().serialize(component));
						}
					}
					return null;
				}
				Class<?> returnType = method.getReturnType();
				if(returnType == boolean.class)
				{
					return false;
				}
				if(returnType == int.class)
				{
					return 0;
				}
				return null;
			});
	}

    private static DirectorSender directorSender() {
        return new DirectorSender() {
            @Override
            public String getName() {
                return "tester";
            }

            @Override
            public boolean isPlayer() {
                return false;
            }

            @Override
            public void sendMessage(String message) {
            }
        };
    }

    private DirectorRuntimeNode findChild(DirectorRuntimeNode root, String name) {
        for (DirectorRuntimeNode child : root.getChildren()) {
            if (child.getDescriptor().getName().equals(name)) {
                return child;
            }
        }

        return null;
    }
}
