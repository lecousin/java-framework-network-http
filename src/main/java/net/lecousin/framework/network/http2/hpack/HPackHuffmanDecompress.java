package net.lecousin.framework.network.http2.hpack;

import net.lecousin.framework.io.data.BitsBuffer;

/** HPack huffman tree. */
public final class HPackHuffmanDecompress {
	
	private HPackHuffmanDecompress() {
		/* no instance. */
	}
	
	public static final Node root =
		new NodeNode(
			new NodeNode(
				new NodeNode(
					new NodeNode(
						new CharChar(Character.valueOf((char)48), Character.valueOf((char)49)),
						new CharChar(Character.valueOf((char)50), Character.valueOf((char)97))
					),
					new NodeNode(
						new CharChar(Character.valueOf((char)99), Character.valueOf((char)101)),
						new CharChar(Character.valueOf((char)105), Character.valueOf((char)111))
					)
				),
				new NodeNode(
					new NodeNode(
						new CharChar(Character.valueOf((char)115), Character.valueOf((char)116)),
						new NodeNode(
							new CharChar(Character.valueOf((char)32), Character.valueOf((char)37)),
							new CharChar(Character.valueOf((char)45), Character.valueOf((char)46))
						)
					),
					new NodeNode(
						new NodeNode(
							new CharChar(Character.valueOf((char)47), Character.valueOf((char)51)),
							new CharChar(Character.valueOf((char)52), Character.valueOf((char)53))
						),
						new NodeNode(
							new CharChar(Character.valueOf((char)54), Character.valueOf((char)55)),
							new CharChar(Character.valueOf((char)56), Character.valueOf((char)57))
						)
					)
				)
			),
			new NodeNode(
				new NodeNode(
					new NodeNode(
						new NodeNode(
							new CharChar(Character.valueOf((char)61), Character.valueOf((char)65)),
							new CharChar(Character.valueOf((char)95), Character.valueOf((char)98))
						),
						new NodeNode(
							new CharChar(Character.valueOf((char)100), Character.valueOf((char)102)),
							new CharChar(Character.valueOf((char)103), Character.valueOf((char)104))
						)
					),
					new NodeNode(
						new NodeNode(
							new CharChar(Character.valueOf((char)108), Character.valueOf((char)109)),
							new CharChar(Character.valueOf((char)110), Character.valueOf((char)112))
						),
						new NodeNode(
							new CharChar(Character.valueOf((char)114), Character.valueOf((char)117)),
							new NodeNode(
								new CharChar(Character.valueOf((char)58), Character.valueOf((char)66)),
								new CharChar(Character.valueOf((char)67), Character.valueOf((char)68))
							)
						)
					)
				),
				new NodeNode(
					new NodeNode(
						new NodeNode(
							new NodeNode(
								new CharChar(Character.valueOf((char)69), Character.valueOf((char)70)),
								new CharChar(Character.valueOf((char)71), Character.valueOf((char)72))
							),
							new NodeNode(
								new CharChar(Character.valueOf((char)73), Character.valueOf((char)74)),
								new CharChar(Character.valueOf((char)75), Character.valueOf((char)76))
							)
						),
						new NodeNode(
							new NodeNode(
								new CharChar(Character.valueOf((char)77), Character.valueOf((char)78)),
								new CharChar(Character.valueOf((char)79), Character.valueOf((char)80))
							),
							new NodeNode(
								new CharChar(Character.valueOf((char)81), Character.valueOf((char)82)),
								new CharChar(Character.valueOf((char)83), Character.valueOf((char)84))
							)
						)
					),
					new NodeNode(
						new NodeNode(
							new NodeNode(
								new CharChar(Character.valueOf((char)85), Character.valueOf((char)86)),
								new CharChar(Character.valueOf((char)87), Character.valueOf((char)89))
							),
							new NodeNode(
								new CharChar(Character.valueOf((char)106), Character.valueOf((char)107)),
								new CharChar(Character.valueOf((char)113), Character.valueOf((char)118))
							)
						),
						new NodeNode(
							new NodeNode(
								new CharChar(Character.valueOf((char)119), Character.valueOf((char)120)),
								new CharChar(Character.valueOf((char)121), Character.valueOf((char)122))
							),
							new NodeNode(
								new NodeNode(
									new CharChar(Character.valueOf((char)38), Character.valueOf((char)42)),
									new CharChar(Character.valueOf((char)44), Character.valueOf((char)59))
								),
								new NodeNode(
									new CharChar(Character.valueOf((char)88), Character.valueOf((char)90)),
new NodeNode(
	new NodeNode(
		new CharChar(Character.valueOf((char)33), Character.valueOf((char)34)),
		new CharChar(Character.valueOf((char)40), Character.valueOf((char)41))
	),
	new NodeNode(
		new CharNode(
			Character.valueOf((char)63),
			new CharChar(Character.valueOf((char)39), Character.valueOf((char)43))
		),
		new NodeNode(
			new CharNode(
				Character.valueOf((char)124),
				new CharChar(Character.valueOf((char)35), Character.valueOf((char)62))
			),
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)0), Character.valueOf((char)36)),
					new CharChar(Character.valueOf((char)64), Character.valueOf((char)91))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)93), Character.valueOf((char)126)),
					new NodeNode(
						new CharChar(Character.valueOf((char)94), Character.valueOf((char)125)),
						new NodeNode(
							new CharChar(Character.valueOf((char)60), Character.valueOf((char)96)),
							new CharNode(
								Character.valueOf((char)123),
								new NodeNode(
new NodeNode(
	new NodeNode(
		new CharChar(Character.valueOf((char)92), Character.valueOf((char)195)),
		new CharNode(
			Character.valueOf((char)208),
			new CharChar(Character.valueOf((char)128), Character.valueOf((char)130))
		)
	),
	new NodeNode(
		new NodeNode(
			new CharChar(Character.valueOf((char)131), Character.valueOf((char)162)),
			new CharChar(Character.valueOf((char)184), Character.valueOf((char)194))
		),
		new NodeNode(
			new CharChar(Character.valueOf((char)224), Character.valueOf((char)226)),
			new NodeNode(
				new CharChar(Character.valueOf((char)153), Character.valueOf((char)161)),
				new CharChar(Character.valueOf((char)167), Character.valueOf((char)172))
			)
		)
	)
),
new NodeNode(
	new NodeNode(
		new NodeNode(
			new NodeNode(
				new CharChar(Character.valueOf((char)176), Character.valueOf((char)177)),
				new CharChar(Character.valueOf((char)179), Character.valueOf((char)209))
			),
			new NodeNode(
				new CharChar(Character.valueOf((char)216), Character.valueOf((char)217)),
				new CharChar(Character.valueOf((char)227), Character.valueOf((char)229))
			)
		),
		new NodeNode(
			new NodeNode(
				new CharNode(
					Character.valueOf((char)230),
					new CharChar(Character.valueOf((char)129), Character.valueOf((char)132))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)133), Character.valueOf((char)134)),
					new CharChar(Character.valueOf((char)136), Character.valueOf((char)146))
				)
			),
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)154), Character.valueOf((char)156)),
					new CharChar(Character.valueOf((char)160), Character.valueOf((char)163))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)164), Character.valueOf((char)169)),
					new CharChar(Character.valueOf((char)170), Character.valueOf((char)173))
				)
			)
		)
	),
	new NodeNode(
		new NodeNode(
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)178), Character.valueOf((char)181)),
					new CharChar(Character.valueOf((char)185), Character.valueOf((char)186))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)187), Character.valueOf((char)189)),
					new CharChar(Character.valueOf((char)190), Character.valueOf((char)196))
				)
			),
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)198), Character.valueOf((char)228)),
					new CharChar(Character.valueOf((char)232), Character.valueOf((char)233))
				),
				new NodeNode(
					new NodeNode(
						new CharChar(Character.valueOf((char)1), Character.valueOf((char)135)),
						new CharChar(Character.valueOf((char)137), Character.valueOf((char)138))
					),
					new NodeNode(
						new CharChar(Character.valueOf((char)139), Character.valueOf((char)140)),
						new CharChar(Character.valueOf((char)141), Character.valueOf((char)143))
					)
				)
			)
		),
		new NodeNode(
			new NodeNode(
				new NodeNode(
					new NodeNode(
						new CharChar(Character.valueOf((char)147), Character.valueOf((char)149)),
						new CharChar(Character.valueOf((char)150), Character.valueOf((char)151))
					),
					new NodeNode(
						new CharChar(Character.valueOf((char)152), Character.valueOf((char)155)),
						new CharChar(Character.valueOf((char)157), Character.valueOf((char)158))
					)
				),
				new NodeNode(
					new NodeNode(
						new CharChar(Character.valueOf((char)165), Character.valueOf((char)166)),
						new CharChar(Character.valueOf((char)168), Character.valueOf((char)174))
					),
					new NodeNode(
						new CharChar(Character.valueOf((char)175), Character.valueOf((char)180)),
						new CharChar(Character.valueOf((char)182), Character.valueOf((char)183))
					)
				)
			),
			new NodeNode(
				new NodeNode(
					new NodeNode(
						new CharChar(Character.valueOf((char)188), Character.valueOf((char)191)),
						new CharChar(Character.valueOf((char)197), Character.valueOf((char)231))
					),
					new NodeNode(
						new CharNode(
							Character.valueOf((char)239),
							new CharChar(Character.valueOf((char)9), Character.valueOf((char)142))
						),
						new NodeNode(
							new CharChar(Character.valueOf((char)144), Character.valueOf((char)145)),
							new CharChar(Character.valueOf((char)148), Character.valueOf((char)159))
						)
					)
				),
				new NodeNode(
					new NodeNode(
						new NodeNode(
							new CharChar(Character.valueOf((char)171), Character.valueOf((char)206)),
							new CharChar(Character.valueOf((char)215), Character.valueOf((char)225))
						),
						new NodeNode(
							new CharChar(Character.valueOf((char)236), Character.valueOf((char)237)),
							new NodeNode(
								new CharChar(Character.valueOf((char)199), Character.valueOf((char)207)),
								new CharChar(Character.valueOf((char)234), Character.valueOf((char)235))
							)
						)
					),
					new NodeNode(
						new NodeNode(
							new NodeNode(
								new NodeNode(
									new CharChar(Character.valueOf((char)192), Character.valueOf((char)193)),
									new CharChar(Character.valueOf((char)200), Character.valueOf((char)201))
								),
								new NodeNode(
									new CharChar(Character.valueOf((char)202), Character.valueOf((char)205)),
									new CharChar(Character.valueOf((char)210), Character.valueOf((char)213))
								)
							),
							new NodeNode(
								new NodeNode(
									new CharChar(Character.valueOf((char)218), Character.valueOf((char)219)),
									new CharChar(Character.valueOf((char)238), Character.valueOf((char)240))
								),
								new NodeNode(
									new CharChar(Character.valueOf((char)242), Character.valueOf((char)243)),
									new CharNode(
										Character.valueOf((char)255),
									new CharChar(Character.valueOf((char)203), Character.valueOf((char)204))
									)
								)
							)
						),
new NodeNode(
	new NodeNode(
		new NodeNode(
			new NodeNode(
				new CharChar(Character.valueOf((char)211), Character.valueOf((char)212)),
				new CharChar(Character.valueOf((char)214), Character.valueOf((char)221))
			),
			new NodeNode(
				new CharChar(Character.valueOf((char)222), Character.valueOf((char)223)),
				new CharChar(Character.valueOf((char)241), Character.valueOf((char)244))
			)
		),
		new NodeNode(
			new NodeNode(
				new CharChar(Character.valueOf((char)245), Character.valueOf((char)246)),
				new CharChar(Character.valueOf((char)247), Character.valueOf((char)248))
			),
			new NodeNode(
				new CharChar(Character.valueOf((char)250), Character.valueOf((char)251)),
				new CharChar(Character.valueOf((char)252), Character.valueOf((char)253))
			)
		)
	),
	new NodeNode(
		new NodeNode(
			new NodeNode(
				new CharNode(
					Character.valueOf((char)254),
					new CharChar(Character.valueOf((char)2), Character.valueOf((char)3))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)4), Character.valueOf((char)5)),
					new CharChar(Character.valueOf((char)6), Character.valueOf((char)7))
				)
			),
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)8), Character.valueOf((char)11)),
					new CharChar(Character.valueOf((char)12), Character.valueOf((char)14))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)15), Character.valueOf((char)16)),
					new CharChar(Character.valueOf((char)17), Character.valueOf((char)18))
				)
			)
		),
		new NodeNode(
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)19), Character.valueOf((char)20)),
					new CharChar(Character.valueOf((char)21), Character.valueOf((char)23))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)24), Character.valueOf((char)25)),
					new CharChar(Character.valueOf((char)26), Character.valueOf((char)27))
				)
			),
			new NodeNode(
				new NodeNode(
					new CharChar(Character.valueOf((char)28), Character.valueOf((char)29)),
					new CharChar(Character.valueOf((char)30), Character.valueOf((char)31))
				),
				new NodeNode(
					new CharChar(Character.valueOf((char)127), Character.valueOf((char)220)),
					new CharNode(
						Character.valueOf((char)249),
						new NodeNode(
							new CharChar(Character.valueOf((char)10), Character.valueOf((char)13)),
							new CharChar(Character.valueOf((char)22), Character.valueOf((char)256))
						)
					)
				)
			)
		)
	)
)
					)
				)
			)
		)
	)
)
								)
							)
						)
					)
				)
			)
		)
	)
)
								)
							)
						)
					)
				)
			)
		);

	/** Huffman Node. */
	public interface Node {
		/** Get a Character or a Node. */
		Object get(BitsBuffer.Readable bits);
	}

	private static class CharChar implements Node {
		
		private CharChar(Character zero, Character one) {
			this.zero = zero;
			this.one = one;
		}
		
		private Character zero;
		private Character one;
		
		@Override
		public Object get(BitsBuffer.Readable bits) {
			boolean b = bits.get();
			if (b)
				return one;
			return zero;
		}
		
	}
	
	private static class CharNode implements Node {
		
		private CharNode(Character zero, Node one) {
			this.zero = zero;
			this.one = one;
		}
		
		private Character zero;
		private Node one;
		
		@Override
		public Object get(BitsBuffer.Readable bits) {
			boolean b = bits.get();
			if (b) {
				if (!bits.hasRemaining())
					return one;
				return one.get(bits);
			}
			return zero;
		}
		
	}

	/*
	private static class NodeChar implements Node {
		
		private NodeChar(Node zero, Character one) {
			this.zero = zero;
			this.one = one;
		}
		
		private Node zero;
		private Character one;
		
		@Override
		public Object get(BitsBuffer.Readable bits) {
			boolean b = bits.get();
			if (!b) {
				if (!bits.hasRemaining())
					return zero;
				return zero.get(bits);
			}
			return one;
		}
		
	}*/
	
	private static class NodeNode implements Node {
		
		private NodeNode(Node zero, Node one) {
			this.zero = zero;
			this.one = one;
		}

		private Node zero;
		private Node one;
		
		@Override
		public Object get(BitsBuffer.Readable bits) {
			boolean b = bits.get();
			if (b) {
				if (!bits.hasRemaining())
					return one;
				return one.get(bits);
			}
			if (!bits.hasRemaining())
				return zero;
			return zero.get(bits);
		}
		
	}
	
}
