package net.lecousin.framework.network.http2.util;

import java.util.Iterator;
import java.util.LinkedList;

public class GenerateHPackHuffman {

	public static void main(String[] args) {
		add(48, 0x0, 5);
		add(49, 0x1, 5);
		add(50, 0x2, 5);
		add(97, 0x3, 5);
		add(99, 0x4, 5);
		add(101, 0x5, 5);
		add(105, 0x6, 5);
		add(111, 0x7, 5);
		add(115, 0x8, 5);
		add(116, 0x9, 5);

		add(32, 0x14, 6);
		add(37, 0x15, 6);
		add(45, 0x16, 6);
		add(46, 0x17, 6);
		add(47, 0x18, 6);
		add(51, 0x19, 6);
		add(52, 0x1a, 6);
		add(53, 0x1b, 6);
		add(54, 0x1c, 6);
		add(55, 0x1d, 6);
		add(56, 0x1e, 6);
		add(57, 0x1f, 6);
		add(61, 0x20, 6);
		add(65, 0x21, 6);
		add(95, 0x22, 6);
		add(98, 0x23, 6);
		add(100, 0x24, 6);
		add(102, 0x25, 6);
		add(103, 0x26, 6);
		add(104, 0x27, 6);
		add(108, 0x28, 6);
		add(109, 0x29, 6);
		add(110, 0x2a, 6);
		add(112, 0x2b, 6);
		add(114, 0x2c, 6);
		add(117, 0x2d, 6);

		add(58, 0x5c, 7);
		add(66, 0x5d, 7);
		add(67, 0x5e, 7);
		add(68, 0x5f, 7);
		add(69, 0x60, 7);
		add(70, 0x61, 7);
		add(71, 0x62, 7);
		add(72, 0x63, 7);
		add(73, 0x64, 7);
		add(74, 0x65, 7);
		add(75, 0x66, 7);
		add(76, 0x67, 7);
		add(77, 0x68, 7);
		add(78, 0x69, 7);
		add(79, 0x6a, 7);
		add(80, 0x6b, 7);
		add(81, 0x6c, 7);
		add(82, 0x6d, 7);
		add(83, 0x6e, 7);
		add(84, 0x6f, 7);
		add(85, 0x70, 7);
		add(86, 0x71, 7);
		add(87, 0x72, 7);
		add(89, 0x73, 7);
		add(106, 0x74, 7);
		add(107, 0x75, 7);
		add(113, 0x76, 7);
		add(118, 0x77, 7);
		add(119, 0x78, 7);
		add(120, 0x79, 7);
		add(121, 0x7a, 7);
		add(122, 0x7b, 7);

		add(38, 0xf8, 8);
		add(42, 0xf9, 8);
		add(44, 0xfa, 8);
		add(59, 0xfb, 8);
		add(88, 0xfc, 8);
		add(90, 0xfd, 8);

		add(33, 0x3f8, 10);
		add(34, 0x3f9, 10);
		add(40, 0x3fa, 10);
		add(41, 0x3fb, 10);
		add(63, 0x3fc, 10);

		add(39, 0x7fa, 11);
		add(43, 0x7fb, 11);
		add(124, 0x7fc, 11);

		add(35, 0xffa, 12);
		add(62, 0xffb, 12);

		add(0, 0x1ff8, 13);
		add(36, 0x1ff9, 13);
		add(64, 0x1ffa, 13);
		add(91, 0x1ffb, 13);
		add(93, 0x1ffc, 13);
		add(126, 0x1ffd, 13);

		add(94, 0x3ffc, 14);
		add(125, 0x3ffd, 14);

		add(60, 0x7ffc, 15);
		add(96, 0x7ffd, 15);
		add(123, 0x7ffe, 15);

		add(92, 0x7fff0, 19);
		add(195, 0x7fff1, 19);
		add(208, 0x7fff2, 19);

		add(128, 0xfffe6, 20);
		add(130, 0xfffe7, 20);
		add(131, 0xfffe8, 20);
		add(162, 0xfffe9, 20);
		add(184, 0xfffea, 20);
		add(194, 0xfffeb, 20);
		add(224, 0xfffec, 20);
		add(226, 0xfffed, 20);

		add(153, 0x1fffdc, 21);
		add(161, 0x1fffdd, 21);
		add(167, 0x1fffde, 21);
		add(172, 0x1fffdf, 21);
		add(176, 0x1fffe0, 21);
		add(177, 0x1fffe1, 21);
		add(179, 0x1fffe2, 21);
		add(209, 0x1fffe3, 21);
		add(216, 0x1fffe4, 21);
		add(217, 0x1fffe5, 21);
		add(227, 0x1fffe6, 21);
		add(229, 0x1fffe7, 21);
		add(230, 0x1fffe8, 21);

		add(129, 0x3fffd2, 22);
		add(132, 0x3fffd3, 22);
		add(133, 0x3fffd4, 22);
		add(134, 0x3fffd5, 22);
		add(136, 0x3fffd6, 22);
		add(146, 0x3fffd7, 22);
		add(154, 0x3fffd8, 22);
		add(156, 0x3fffd9, 22);
		add(160, 0x3fffda, 22);
		add(163, 0x3fffdb, 22);
		add(164, 0x3fffdc, 22);
		add(169, 0x3fffdd, 22);
		add(170, 0x3fffde, 22);
		add(173, 0x3fffdf, 22);
		add(178, 0x3fffe0, 22);
		add(181, 0x3fffe1, 22);
		add(185, 0x3fffe2, 22);
		add(186, 0x3fffe3, 22);
		add(187, 0x3fffe4, 22);
		add(189, 0x3fffe5, 22);
		add(190, 0x3fffe6, 22);
		add(196, 0x3fffe7, 22);
		add(198, 0x3fffe8, 22);
		add(228, 0x3fffe9, 22);
		add(232, 0x3fffea, 22);
		add(233, 0x3fffeb, 22);

		add(1, 0x7fffd8, 23);
		add(135, 0x7fffd9, 23);
		add(137, 0x7fffda, 23);
		add(138, 0x7fffdb, 23);
		add(139, 0x7fffdc, 23);
		add(140, 0x7fffdd, 23);
		add(141, 0x7fffde, 23);
		add(143, 0x7fffdf, 23);
		add(147, 0x7fffe0, 23);
		add(149, 0x7fffe1, 23);
		add(150, 0x7fffe2, 23);
		add(151, 0x7fffe3, 23);
		add(152, 0x7fffe4, 23);
		add(155, 0x7fffe5, 23);
		add(157, 0x7fffe6, 23);
		add(158, 0x7fffe7, 23);
		add(165, 0x7fffe8, 23);
		add(166, 0x7fffe9, 23);
		add(168, 0x7fffea, 23);
		add(174, 0x7fffeb, 23);
		add(175, 0x7fffec, 23);
		add(180, 0x7fffed, 23);
		add(182, 0x7fffee, 23);
		add(183, 0x7fffef, 23);
		add(188, 0x7ffff0, 23);
		add(191, 0x7ffff1, 23);
		add(197, 0x7ffff2, 23);
		add(231, 0x7ffff3, 23);
		add(239, 0x7ffff4, 23);

		add(9, 0xffffea, 24);
		add(142, 0xffffeb, 24);
		add(144, 0xffffec, 24);
		add(145, 0xffffed, 24);
		add(148, 0xffffee, 24);
		add(159, 0xffffef, 24);
		add(171, 0xfffff0, 24);
		add(206, 0xfffff1, 24);
		add(215, 0xfffff2, 24);
		add(225, 0xfffff3, 24);
		add(236, 0xfffff4, 24);
		add(237, 0xfffff5, 24);

		add(199, 0x1ffffec, 25);
		add(207, 0x1ffffed, 25);
		add(234, 0x1ffffee, 25);
		add(235, 0x1ffffef, 25);

		add(192, 0x3ffffe0, 26);
		add(193, 0x3ffffe1, 26);
		add(200, 0x3ffffe2, 26);
		add(201, 0x3ffffe3, 26);
		add(202, 0x3ffffe4, 26);
		add(205, 0x3ffffe5, 26);
		add(210, 0x3ffffe6, 26);
		add(213, 0x3ffffe7, 26);
		add(218, 0x3ffffe8, 26);
		add(219, 0x3ffffe9, 26);
		add(238, 0x3ffffea, 26);
		add(240, 0x3ffffeb, 26);
		add(242, 0x3ffffec, 26);
		add(243, 0x3ffffed, 26);
		add(255, 0x3ffffee, 26);

		add(203, 0x7ffffde, 27);
		add(204, 0x7ffffdf, 27);
		add(211, 0x7ffffe0, 27);
		add(212, 0x7ffffe1, 27);
		add(214, 0x7ffffe2, 27);
		add(221, 0x7ffffe3, 27);
		add(222, 0x7ffffe4, 27);
		add(223, 0x7ffffe5, 27);
		add(241, 0x7ffffe6, 27);
		add(244, 0x7ffffe7, 27);
		add(245, 0x7ffffe8, 27);
		add(246, 0x7ffffe9, 27);
		add(247, 0x7ffffea, 27);
		add(248, 0x7ffffeb, 27);
		add(250, 0x7ffffec, 27);
		add(251, 0x7ffffed, 27);
		add(252, 0x7ffffee, 27);
		add(253, 0x7ffffef, 27);
		add(254, 0x7fffff0, 27);

		add(2, 0xfffffe2, 28);
		add(3, 0xfffffe3, 28);
		add(4, 0xfffffe4, 28);
		add(5, 0xfffffe5, 28);
		add(6, 0xfffffe6, 28);
		add(7, 0xfffffe7, 28);
		add(8, 0xfffffe8, 28);
		add(11, 0xfffffe9, 28);
		add(12, 0xfffffea, 28);
		add(14, 0xfffffeb, 28);
		add(15, 0xfffffec, 28);
		add(16, 0xfffffed, 28);
		add(17, 0xfffffee, 28);
		add(18, 0xfffffef, 28);
		add(19, 0xffffff0, 28);
		add(20, 0xffffff1, 28);
		add(21, 0xffffff2, 28);
		add(23, 0xffffff3, 28);
		add(24, 0xffffff4, 28);
		add(25, 0xffffff5, 28);
		add(26, 0xffffff6, 28);
		add(27, 0xffffff7, 28);
		add(28, 0xffffff8, 28);
		add(29, 0xffffff9, 28);
		add(30, 0xffffffa, 28);
		add(31, 0xffffffb, 28);
		add(127, 0xffffffc, 28);
		add(220, 0xffffffd, 28);
		add(249, 0xffffffe, 28);

		add(10, 0x3ffffffc, 30);
		add(13, 0x3ffffffd, 30);
		add(22, 0x3ffffffe, 30);
		add(256, 0x3fffffff, 30);
		
		System.out.println(" ----- Decompression tree -----");
		root.generate(0);
		System.out.println();
		System.out.println(" ----- Compression table -----");
		System.out.println("int[] table = new int[] {");
		for (int i = 0; i < 257; ++i) {
			System.out.println("\t" + table[i * 2] + ", " + table[i * 2 + 1] + ",");
		}
		System.out.println("};");
	}
	
	private static class Node {
		
		private Object zero;
		private Object one;
		
		private void add(Iterator<Boolean> bits, Integer c) {
			Boolean b = bits.next();
			if (!bits.hasNext()) {
				if (b.booleanValue())
					one = c;
				else
					zero = c;
				return;
			}
			if (b.booleanValue()) {
				if (one == null) one = new Node();
				((Node)one).add(bits, c);
			} else {
				if (zero == null) zero = new Node();
				((Node)zero).add(bits, c);
			}
		}
		
		private void generate(int indent) {
			if (zero instanceof Integer) {
				if (one instanceof Integer) {
					indent(indent);
					System.out.print("new CharChar(Character.valueOf((char)" + zero + "), Character.valueOf((char)" + one + "))");
					return;
				}
				indent(indent);
				System.out.println("new CharNode(");
				indent(indent + 1);
				System.out.println("Character.valueOf((char)" + zero + "),");
				((Node)one).generate(indent + 1);
				System.out.println();
				indent(indent);
				System.out.print(")");
				return;
			}
			if (one instanceof Integer) {
				indent(indent);
				System.out.println("new NodeChar(");
				((Node)zero).generate(indent + 1);
				System.out.println();
				indent(indent + 1);
				System.out.println("Character.valueOf((char)" + one + ")");
				indent(indent);
				System.out.print(")");
				return;
			}
			indent(indent);
			System.out.println("new NodeNode(");
			((Node)zero).generate(indent + 1);
			System.out.println(",");
			((Node)one).generate(indent + 1);
			System.out.println();
			indent(indent);
			System.out.print(")");
		}
		
	}

	private static void indent(int indent) {
		for (int i = 0; i < indent; ++i)
			System.out.print("\t");
	}

	private static Node root = new Node();
	private static int[] table = new int[257 * 2];
	
	private static void add(int charValue, int encodedValue, int nbBits) {
		LinkedList<Boolean> bits = new LinkedList<>();
		for (int i = 0; i < nbBits; ++i) {
			int mask = 1 << i;
			boolean bit = (encodedValue & mask) != 0;
			bits.addFirst(Boolean.valueOf(bit));
		}
		Node n = root;
		n.add(bits.iterator(), Integer.valueOf(charValue));
		
		table[charValue * 2] = encodedValue;
		table[charValue * 2 + 1] = nbBits;
	}
	
}
